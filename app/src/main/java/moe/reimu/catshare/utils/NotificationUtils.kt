package moe.reimu.catshare.utils

import android.app.PendingIntent
import android.content.Context
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.reimu.catshare.R

enum class LiveStage(val progress: Int) {
    PREPARING(10),
    CONNECTING(30),
    TRANSFERRING(70),
    FINALIZING(95),
    COMPLETED(100)
}

object NotificationUtils {
    const val RECEIVER_FG_CHAN_ID = "RECEIVER_FG"
    const val SENDER_CHAN_ID = "SENDER"
    const val RECEIVER_CHAN_ID = "RECEIVER"
    const val OTHER_CHAN_ID = "OTHER"

    const val GATT_SERVER_FG_ID = 1
    const val RECEIVER_FG_ID = 2
    const val SENDER_FG_ID = 3

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        val channels = listOf(
            NotificationChannelCompat.Builder(
                RECEIVER_FG_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            ).setName("Receiver persistent notification (can be disabled)").build(),
            NotificationChannelCompat.Builder(
                SENDER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName("Sending files").build(),
            NotificationChannelCompat.Builder(
                RECEIVER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName("Receiving files").build(),
            NotificationChannelCompat.Builder(
                OTHER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName("Other notifications").build(),
        )

        manager.createNotificationChannelsCompat(channels)
    }

    fun getLiveNotificationBuilder(
        context: Context,
        channelId: String,
        stage: LiveStage,
        targetName: String,
        cancelIntent: PendingIntent? = null
    ): NotificationCompat.Builder {
        val title = when (stage) {
            LiveStage.COMPLETED -> context.getString(R.string.send_ok)
            else -> context.getString(if (channelId == SENDER_CHAN_ID) R.string.sending else R.string.receiving)
        }

        val content = when (stage) {
            LiveStage.PREPARING -> context.getString(R.string.preparing_transmission)
            LiveStage.CONNECTING -> context.getString(R.string.noti_connecting)
            LiveStage.TRANSFERRING -> context.getString(R.string.transferring_files)
            LiveStage.FINALIZING -> context.getString(R.string.finishing)
            LiveStage.COMPLETED -> context.getString(R.string.done)
        }

        val icon = if (stage == LiveStage.COMPLETED) R.drawable.ic_done else R.drawable.ic_downloading

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText(targetName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setRequestPromotedOngoing(true)
            .setProgress(100, stage.progress, false)
            .setOnlyAlertOnce(true)
            .setOngoing(stage != LiveStage.COMPLETED)

        cancelIntent?.let {
            builder.addAction(R.drawable.ic_close, context.getString(android.R.string.cancel), it)
        }

        return builder
    }

    fun showBusyToast(context: Context) {
        Toast.makeText(context, R.string.app_busy_toast, Toast.LENGTH_LONG).show()
    }

    fun showBluetoothToast(context: Context) {
        Toast.makeText(context, R.string.bluetooth_disabled, Toast.LENGTH_LONG).show()
    }

    fun showWifiToast(context: Context) {
        Toast.makeText(context, R.string.wifi_disabled, Toast.LENGTH_LONG).show()
    }
}