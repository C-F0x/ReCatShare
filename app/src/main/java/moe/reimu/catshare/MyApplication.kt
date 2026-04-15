package moe.reimu.catshare

import android.app.Application
import android.util.Log
import moe.reimu.catshare.utils.INTERNAL_BROADCAST_PERMISSION
import moe.reimu.catshare.utils.NotificationUtils
import moe.reimu.catshare.utils.ServiceState
import moe.reimu.catshare.utils.TAG
import java.util.concurrent.atomic.AtomicBoolean

class MyApplication : Application() {
    private val isBusy = AtomicBoolean()

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationUtils.createChannels(this)
    }

    fun setBusy() = if (isBusy.compareAndSet(false, true)) {
        Log.i(TAG, "Setting busy flag")
        sendBroadcast(
            ServiceState.getBusyChangedIntent(true),
            INTERNAL_BROADCAST_PERMISSION
        )
        true
    } else {
        false
    }

    fun clearBusy() {
        Log.i(TAG, "Clearing busy flag")
        isBusy.set(false)
        sendBroadcast(
            ServiceState.getBusyChangedIntent(false),
            INTERNAL_BROADCAST_PERMISSION
        )
    }

    fun getBusy() = isBusy.get()

    companion object {
        const val ACTION_BUSY_CHANGED = "moe.reimu.catshare.BUSY_CHANGED"

        private var instance: MyApplication? = null
        fun getInstance() = instance!!
    }
}