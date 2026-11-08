package net.ohrtech.device

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.ColorInt
import net.ohrtech.device.aidl.IOhrDeviceSdk
import net.ohrtech.device.aidl.IOhrScreen
import net.ohrtech.device.aidl.IOhrScreenCallback
import net.ohrtech.device.aidl.IOhrScreens
import java.util.concurrent.Executors

/**
 * The Wi-Fi screen of Android's Settings, drawn by the OhrTech Agent inside the app's own layout
 * (SurfaceControlViewHost): the network list, joining every type of network, saved networks,
 * forgetting. Touch, keyboard and TalkBack work as in the app's own views. It opens no other
 * activity or window, so a kiosk app stays a kiosk. Needs the fleet's "wifi" grant.
 *
 * ```
 * <net.ohrtech.device.OhrWifiView android:id="@+id/wifi" android:layout_width="match_parent" android:layout_height="match_parent" />
 * wifiView.attach(sdk)
 * ```
 * With `layout_height="wrap_content"` the view takes the screen's own height.
 */
class OhrWifiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs) {

    /** What the screen tells the app. */
    interface Listener {
        /** The device joined [ssid]. */
        fun onConnected(ssid: String) {}
        /** The screen couldn't open or stopped: [error] says why (an [OhrException] when the Agent refused). */
        fun onError(error: Exception) {}
    }

    /** The look. Unset values follow the system's Material You colours, as Settings does. */
    data class Theme @JvmOverloads constructor(
        val dark: Boolean? = null,
        @ColorInt val accent: Int? = null,
        @ColorInt val background: Int? = null,
        @ColorInt val text: Int? = null,
        val cornerRadiusDp: Float? = null,
    ) {
        internal fun bundle() = Bundle().apply {
            dark?.let { putBoolean(IOhrScreens.THEME_DARK, it) }
            accent?.let { putInt(IOhrScreens.THEME_ACCENT, it) }
            background?.let { putInt(IOhrScreens.THEME_BACKGROUND, it) }
            text?.let { putInt(IOhrScreens.THEME_TEXT, it) }
            cornerRadiusDp?.let { putFloat(IOhrScreens.THEME_CORNER_RADIUS_DP, it) }
        }
    }

    /** What the screen offers (all on by default). */
    data class Options @JvmOverloads constructor(
        val wifiSwitch: Boolean = true,
        val addNetwork: Boolean = true,
        val enterprise: Boolean = true,
        val forget: Boolean = true,
        val saved: Boolean = true,
    ) {
        internal fun bundle() = Bundle().apply {
            putBoolean(IOhrScreens.WIFI_SWITCH, wifiSwitch)
            putBoolean(IOhrScreens.WIFI_ADD_NETWORK, addNetwork)
            putBoolean(IOhrScreens.WIFI_ENTERPRISE, enterprise)
            putBoolean(IOhrScreens.WIFI_FORGET, forget)
            putBoolean(IOhrScreens.WIFI_SAVED, saved)
        }
    }

    var listener: Listener? = null
    var options: Options = Options()

    var theme: Theme = Theme()
        set(value) {
            field = value
            screen?.let { s -> io.execute { runCatching { s.setTheme(value.bundle()) } } }
        }

    private var sdk: OhrDeviceSdk? = null
    private var screen: IOhrScreen? = null
    private var opening = false
    private var contentHeight = 0
    private val main = Handler(Looper.getMainLooper())

    init {
        // Above the window, so the embedded screen gets input (and the keyboard) like a view.
        setZOrderOnTop(true)
    }

    /** Shows the screen (once the view is laid out), through [sdk]. */
    fun attach(sdk: OhrDeviceSdk) {
        this.sdk = sdk
        if (isAttachedToWindow && width > 0 && height > 0) open()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = screen
        if (s == null) {
            if (sdk != null && w > 0 && h > 0) open()
        } else {
            io.execute { runCatching { s.resize(w, h) } }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // wrap_content: the screen's own height once it said, else a sensible minimum.
        if (layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val h = if (contentHeight > 0) contentHeight else (320 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(measuredWidth, resolveSize(h, heightMeasureSpec))
        }
    }

    override fun onDetachedFromWindow() {
        val s = screen
        screen = null
        if (s != null) io.execute { runCatching { s.close() } }
        super.onDetachedFromWindow()
    }

    private fun open() {
        val sdk = sdk ?: return
        if (opening || screen != null) return
        val token = hostToken ?: return
        val displayId = display?.displayId ?: return
        opening = true
        val w = width
        val h = height
        val themeBundle = theme.bundle()
        val optionsBundle = options.bundle()
        io.execute {
            try {
                val screens = IOhrScreens.Stub.asInterface(sdk.area(IOhrDeviceSdk.AREA_SCREENS))
                val out = agentCall {
                    screens.open(IOhrScreens.SCREEN_WIFI, token, displayId, w, h, themeBundle, optionsBundle, callback)
                }
                val pkg = out.getParcelable(IOhrScreens.KEY_SURFACE_PACKAGE, SurfaceControlViewHost.SurfacePackage::class.java)
                val s = IOhrScreen.Stub.asInterface(out.getBinder(IOhrScreens.KEY_SCREEN))
                main.post {
                    opening = false
                    if (!isAttachedToWindow || pkg == null) {
                        runCatching { io.execute { s.close() } }
                        return@post
                    }
                    screen = s
                    setChildSurfacePackage(pkg)
                    if (width != w || height != h) io.execute { runCatching { s.resize(width, height) } }
                }
            } catch (e: Exception) {
                main.post {
                    opening = false
                    listener?.onError(e)
                }
            }
        }
    }

    private val callback = object : IOhrScreenCallback.Stub() {
        override fun onEvent(event: String, data: Bundle) {
            main.post {
                when (event) {
                    "connected" -> listener?.onConnected(data.getString("ssid") ?: "")
                    "error" -> listener?.onError(OhrException.Failed(data.getString("message") ?: "error"))
                    "closed" -> screen = null
                    "height" -> {
                        contentHeight = data.getInt("px")
                        if (layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) requestLayout()
                    }
                }
            }
        }
    }

    private companion object {
        /** Binder calls off the main thread, in order. */
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "OhrWifiView").apply { isDaemon = true } }
    }
}
