package moe.reimu.catshare.utils

import android.content.Intent
import moe.reimu.catshare.BuildConfig

object ServiceState {
    const val ACTION_QUERY_RECEIVER_STATE = "${BuildConfig.APPLICATION_ID}.QUERY_RECEIVER_STATE"
    const val ACTION_UPDATE_RECEIVER_STATE = "${BuildConfig.APPLICATION_ID}.UPDATE_RECEIVER_STATE"
    const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_SERVICE"
    const val ACTION_BUSY_CHANGED = "${BuildConfig.APPLICATION_ID}.BUSY_CHANGED"

    fun getQueryIntent() = Intent(ACTION_QUERY_RECEIVER_STATE)

    fun getUpdateIntent(
        isRunning: Boolean,
        progress: Float = 0f,
        progressText: String = "",
        isFinishing: Boolean = false
    ) = Intent(ACTION_UPDATE_RECEIVER_STATE).apply {
        putExtra("isRunning", isRunning)
        putExtra("progress", progress)
        putExtra("progressText", progressText)
        putExtra("isFinishing", isFinishing)
    }

    fun getStopIntent() = Intent(ACTION_STOP_SERVICE)

    fun getBusyChangedIntent(busy: Boolean) = Intent(ACTION_BUSY_CHANGED).apply {
        putExtra("busy", busy)
    }
}