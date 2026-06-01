package moe.reimu.catshare.utils

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.reimu.catshare.R
import moe.reimu.catshare.models.LiveUpdatePriority
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
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName("Receiver persistent notification").build(),
            NotificationChannelCompat.Builder(
                SENDER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName("Sending files").build(),
            NotificationChannelCompat.Builder(
                RECEIVER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName("Receiving files").build(),
            NotificationChannelCompat.Builder(
                OTHER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName("Other notifications").build(),
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
            .setPriority(if (state.priority == LiveUpdatePriority.CRITICAL) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(state.ongoing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)

        if (state.progress >= 0 || state.indeterminate) {
            builder.setProgress(100, state.progress, state.indeterminate)
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
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

    fun getLiveNotificationBuilder(
        context: Context,
        channelId: String,
        stage: LiveStage,
        targetName: String,
        internalProgress: Int = 0,
        cancelIntent: PendingIntent? = null
    ): NotificationCompat.Builder {
        val title = when (stage) {
            LiveStage.COMPLETED -> context.getString(R.string.send_ok)
            else -> context.getString(if (channelId == SENDER_CHAN_ID) R.string.sending else R.string.receiving)
        }

        val content = when (stage) {
            LiveStage.INIT -> context.getString(R.string.preparing_transmission)
            LiveStage.PREPARING -> context.getString(R.string.preparing_transmission)
            LiveStage.REQUESTED -> context.getString(R.string.response_waiting)
            LiveStage.HANDSHAKE -> context.getString(R.string.noti_connecting)
            LiveStage.WAITING_AUTH -> context.getString(R.string.auth_waiting)
            LiveStage.TRANSFERRING -> context.getString(R.string.transferring_files)
            LiveStage.FINALIZING -> context.getString(R.string.finishing)
            LiveStage.COMPLETED -> context.getString(R.string.done)
        }

        val displayProgress = if (stage == LiveStage.TRANSFERRING) {
            40 + (internalProgress * 0.5).toInt()
        } else {
            stage.progress
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(if (stage == LiveStage.COMPLETED) R.drawable.ic_done else R.drawable.ic_downloading)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText(targetName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setProgress(100, displayProgress, stage == LiveStage.INIT)
            .setOnlyAlertOnce(true)
            .setOngoing(stage != LiveStage.COMPLETED)

        cancelIntent?.let {
            builder.addAction(R.drawable.ic_close, context.getString(android.R.string.cancel), it)
        }

        return builder
    }

    fun getReceiverStandbyNotification(
        context: Context,
        stopIntent: PendingIntent,
        mode: Int,
        progressCurrent: Int,
        statusText: String?
    ): Notification {
        val contentText = if (statusText != null)
            "${context.getString(R.string.discoverable_desc)}  •  $statusText"
        else
            context.getString(R.string.discoverable_desc)

        val builder = NotificationCompat.Builder(context, RECEIVER_FG_CHAN_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_searching)
            .setContentTitle(context.getString(R.string.noti_receiver_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, context.getString(R.string.stop), stopIntent)

        if (mode != 0) {
            builder.setProgress(100, progressCurrent, false)
        }

        return builder.build()
    }

    fun getReceiverBusyNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, RECEIVER_FG_CHAN_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_searching)
            .setContentTitle(context.getString(R.string.noti_receiver_title))
            .setContentText(context.getString(R.string.discoverable_desc))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
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
