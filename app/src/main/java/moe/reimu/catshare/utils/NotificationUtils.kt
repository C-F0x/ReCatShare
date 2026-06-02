package moe.reimu.catshare.utils

import android.app.Notification
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.reimu.catshare.R
import moe.reimu.catshare.models.LiveUpdateState

enum class LiveStage(val progress: Int) {
    INIT(0),
    PREPARING(10),
    REQUESTED(20),
    HANDSHAKE(30),
    WAITING_AUTH(40),
    TRANSFERRING(40),
    FINALIZING(95),
    COMPLETED(100)
}

object NotificationUtils {
    const val RECEIVER_FG_CHAN_ID = "RECEIVER_FG_LIVE"
    const val SENDER_CHAN_ID = "SENDER_LIVE"
    const val RECEIVER_CHAN_ID = "RECEIVER_LIVE"
    const val OTHER_CHAN_ID = "OTHER"

    const val ID_LIVE_UPDATE = 1

    @Deprecated("Use ID_LIVE_UPDATE", ReplaceWith("NotificationUtils.ID_LIVE_UPDATE"))
    const val GATT_SERVER_FG_ID = 1
    @Deprecated("Use ID_LIVE_UPDATE", ReplaceWith("NotificationUtils.ID_LIVE_UPDATE"))
    const val RECEIVER_FG_ID = 1
    @Deprecated("Use ID_LIVE_UPDATE", ReplaceWith("NotificationUtils.ID_LIVE_UPDATE"))
    const val SENDER_FG_ID = 1

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        val channels = listOf(
            NotificationChannelCompat.Builder(
                RECEIVER_FG_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(R.string.noti_chan_receiver_persistent)).build(),
            NotificationChannelCompat.Builder(
                SENDER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(R.string.noti_chan_sending)).build(),
            NotificationChannelCompat.Builder(
                RECEIVER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(R.string.noti_chan_receiving)).build(),
            NotificationChannelCompat.Builder(
                OTHER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName(context.getString(R.string.noti_chan_other)).build(),
        )

        manager.createNotificationChannelsCompat(channels)
    }

    fun buildNotificationFromState(context: Context, state: LiveUpdateState): Notification {
        val channelId = state.channelId ?: RECEIVER_FG_CHAN_ID
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(state.smallIcon ?: R.drawable.ic_bluetooth_searching)
            .setContentTitle(state.title)
            .setContentText(state.content)
            .setSubText(state.subText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setOngoing(state.ongoing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state.ongoing) {
            builder.setRequestPromotedOngoing(true)
            builder.setLocalOnly(true)
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        }

        if (state.progress >= 0 || state.indeterminate) {
            builder.setProgress(100, state.progress, state.indeterminate)
        }

        state.shortCriticalText?.let {
            builder.setShortCriticalText(it.take(7))
        }

        if (state.usesChronometer) {
            builder.setWhen(state.whenTime)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setChronometerCountDown(state.chronometerCountDown)
            }
        }

        state.cancelIntent?.let {
            builder.addAction(R.drawable.ic_close, state.cancelLabel ?: context.getString(android.R.string.cancel), it)
        }

        state.acceptIntent?.let {
            builder.addAction(R.drawable.ic_done, context.getString(R.string.accept), it)
        }

        state.rejectIntent?.let {
            builder.addAction(R.drawable.ic_close, context.getString(R.string.reject), it)
        }

        return builder.build()
    }

    fun getCurrentLiveNotification(context: Context): Notification {
        return buildNotificationFromState(context, LiveUpdateCoordinator.state.value)
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
