package net.ohrtech.device.sample

import android.util.Log
import net.ohrtech.device.push.OhrPushMessage
import net.ohrtech.device.push.OhrPushService
import net.ohrtech.device.push.PushToken

/** Logs what arrives; a real app would act on message.data. */
class SamplePushService : OhrPushService() {
    override fun onMessage(message: OhrPushMessage) {
        Log.i(MainActivity.TAG, "push ${message.id} (${message.pushId}): ${String(message.data)}")
    }

    override fun onTokenChanged(token: PushToken?) {
        Log.i(MainActivity.TAG, "push token now $token")
    }

    override fun onDropped(pushId: String, reason: String) {
        Log.w(MainActivity.TAG, "push $pushId dropped: $reason")
    }
}
