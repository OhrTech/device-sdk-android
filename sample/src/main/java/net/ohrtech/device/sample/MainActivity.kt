package net.ohrtech.device.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ohrtech.device.OhrAccess
import net.ohrtech.device.OhrDevice
import net.ohrtech.device.OhrException
import net.ohrtech.device.OhrKiosk
import net.ohrtech.device.OhrWifiView
import net.ohrtech.device.push.OhrPush
import net.ohrtech.device.push.SenderKey

/**
 * A kiosk app's page: who the device is, kiosk on and off, push, and the Agent's Wi-Fi screen
 * embedded below. Start it with your server's sender key to register for push:
 * `am start -n net.ohrtech.device.sample/.MainActivity --es senderKey <Ed25519 public key, base64>`
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var wifi: OhrWifiView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(32, 32, 32, 0)
        }
        status = TextView(this).apply { textSize = 16f }
        val buttons = LinearLayout(this)
        fun button(label: String, action: suspend () -> String) = Button(this).apply {
            text = label
            setOnClickListener { run(label, action) }
            buttons.addView(this)
        }
        button("Kiosk on") { OhrKiosk.start(this@MainActivity); "kiosk on" }
        button("Kiosk off") { OhrKiosk.stop(); "kiosk off" }
        button("Push") {
            val key = intent.getStringExtra("senderKey") ?: return@button "start with --es senderKey <base64>"
            val t = OhrPush.register(listOf(SenderKey.fromBase64(key)))
            Log.i(TAG, "push token ${t.token} publicKey ${t.publicKeyBase64}")
            "push token ${t.token}"
        }
        wifi = OhrWifiView(this).apply {
            listener = object : OhrWifiView.Listener {
                override fun onConnected(ssid: String) = show("joined $ssid")
                override fun onError(error: Exception) = show("Wi-Fi screen: ${error.message}")
            }
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(wifi, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        scope.launch {
            try {
                val info = OhrDevice.infoAsync()
                val access = OhrAccess.infoAsync()
                show("${info.deviceId} · ${info.fleet?.name} · Agent ${info.agentVersion}\nmay use: ${access.areas.sorted().joinToString()}")
            } catch (e: OhrException) {
                show("Device SDK: ${e.message}")
            }
        }
    }

    private fun run(label: String, action: suspend () -> String) {
        scope.launch {
            show(try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: OhrException) {
                "$label: ${e.javaClass.simpleName}: ${e.message}"
            })
        }
    }

    private fun show(text: String) {
        Log.i(TAG, text)
        status.text = text
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "OhrSdkSample"
    }
}
