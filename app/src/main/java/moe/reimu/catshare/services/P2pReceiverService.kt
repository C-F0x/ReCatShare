package moe.reimu.catshare.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.BuildConfig
import moe.reimu.catshare.FakeTrustManager
import moe.reimu.catshare.MyApplication
import moe.reimu.catshare.R
import moe.reimu.catshare.exceptions.CancelledByUserException
import moe.reimu.catshare.exceptions.ExceptionWithMessage
import moe.reimu.catshare.models.LiveUpdatePriority
import moe.reimu.catshare.models.LiveUpdateState
import moe.reimu.catshare.models.P2pInfo
import moe.reimu.catshare.models.ReceivedFile
import moe.reimu.catshare.models.WebSocketMessage
import moe.reimu.catshare.utils.DeviceUtils
import moe.reimu.catshare.utils.LiveStage
import moe.reimu.catshare.utils.LiveUpdateCoordinator
import moe.reimu.catshare.utils.NotificationUtils
import moe.reimu.catshare.utils.ProgressCounter
import moe.reimu.catshare.utils.TAG
import moe.reimu.catshare.utils.ZipPathValidatorCallback
import moe.reimu.catshare.utils.awaitWithTimeout
import moe.reimu.catshare.utils.checkP2pPermissions
import moe.reimu.catshare.utils.connectSuspend
import moe.reimu.catshare.utils.registerInternalBroadcastReceiver
import moe.reimu.catshare.utils.removeGroupSuspend
import moe.reimu.catshare.utils.requestGroupInfo
import moe.reimu.catshare.utils.sendStatusIgnoreException
import okhttp3.ConnectionPool
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import kotlin.math.min
import kotlin.random.Random

class P2pReceiverService : BaseP2pService() {
    private lateinit var notificationManager: NotificationManagerCompat
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun updateStage(taskId: Int, senderName: String, stage: LiveStage, progress: Int = 0, currentFile: String? = null) {
        val cancelIntent = if (stage != LiveStage.COMPLETED) {
            PendingIntent.getBroadcast(
                this, taskId,
                Intent(ACTION_CANCEL_RECEIVING).apply { putExtra("taskId", taskId) },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val title = when (stage) {
            LiveStage.COMPLETED -> getString(R.string.recv_ok)
            else -> getString(R.string.receiving)
        }

        val content = when (stage) {
            LiveStage.TRANSFERRING -> currentFile ?: getString(R.string.transferring_files)
            LiveStage.INIT -> getString(R.string.preparing_transmission)
            LiveStage.PREPARING -> getString(R.string.preparing_transmission)
            LiveStage.REQUESTED -> getString(R.string.response_waiting)
            LiveStage.HANDSHAKE -> getString(R.string.noti_connecting)
            LiveStage.WAITING_AUTH -> getString(R.string.auth_waiting)
            LiveStage.FINALIZING -> getString(R.string.finishing)
            LiveStage.COMPLETED -> getString(R.string.done)
        }

        val displayProgress = if (stage == LiveStage.TRANSFERRING) {
            40 + (progress * 0.5).toInt()
        } else {
            stage.progress
        }

        val shortText = when (stage) {
            LiveStage.TRANSFERRING -> "$progress%"
            LiveStage.INIT, LiveStage.PREPARING -> getString(R.string.stage_prep)
            LiveStage.HANDSHAKE -> getString(R.string.stage_conn)
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> getString(R.string.stage_wait)
            LiveStage.FINALIZING -> getString(R.string.stage_fin)
            LiveStage.COMPLETED -> getString(R.string.stage_done)
        }

        val state = LiveUpdateState(
            title = title,
            content = content,
            subText = if (stage == LiveStage.TRANSFERRING) "From $senderName" else senderName,
            progress = if (stage != LiveStage.COMPLETED) displayProgress else -1,
            shortCriticalText = shortText,
            priority = LiveUpdatePriority.CRITICAL,
            ongoing = stage != LiveStage.COMPLETED,
            cancelIntent = cancelIntent,
            cancelLabel = if (stage == LiveStage.WAITING_AUTH) getString(R.string.ignore) else null,
            acceptIntent = if (stage == LiveStage.WAITING_AUTH) {
                PendingIntent.getBroadcast(
                    this, taskId,
                    Intent(ACTION_ACCEPTED).apply { putExtra("taskId", taskId) },
                    PendingIntent.FLAG_IMMUTABLE
                )
            } else null,
            rejectIntent = if (stage == LiveStage.WAITING_AUTH) {
                PendingIntent.getBroadcast(
                    this, taskId,
                    Intent(ACTION_DISMISSED).apply { putExtra("taskId", taskId) },
                    PendingIntent.FLAG_IMMUTABLE
                )
            } else null,
            channelId = NotificationUtils.RECEIVER_CHAN_ID,
            smallIcon = if (stage == LiveStage.COMPLETED) R.drawable.ic_done else R.drawable.ic_downloading
        )

        LiveUpdateCoordinator.publishState("RECEIVER", state)
        updateForeground()
    }

    private fun updateForeground() {
        startForeground(
            NotificationUtils.ID_LIVE_UPDATE,
            NotificationUtils.getCurrentLiveNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CANCEL_RECEIVING -> {
                    cancel(intent.getIntExtra("taskId", -1))
                }
            }
        }
    }
    private var internalReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        if (!checkP2pPermissions()) {
            stopSelf()
            return
        }

        notificationManager = NotificationManagerCompat.from(this)

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter(ACTION_CANCEL_RECEIVING))
        internalReceiverRegistered = true
    }

    private var p2pFuture = CompletableDeferred<Pair<WifiP2pInfo, WifiP2pGroup>>()

    @Suppress("DEPRECATION")
    override fun onP2pBroadcast(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val connInfo = intent.getParcelableExtra<WifiP2pInfo>(
                    WifiP2pManager.EXTRA_WIFI_P2P_INFO
                )!!
                val group = intent.getParcelableExtra<WifiP2pGroup>(
                    WifiP2pManager.EXTRA_WIFI_P2P_GROUP
                )
                Log.d(TAG, "P2P info: $connInfo, P2P group: $group")

                if (connInfo.groupFormed && !connInfo.isGroupOwner && group != null) {
                    p2pFuture.complete(Pair(connInfo, group))
                }
            }
        }
    }


    private val currentTaskLock = Any()
    private var currentJob: Job? = null
    private var currentTaskId: Int? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        if (!MyApplication.getInstance().setBusy()) {
            Log.i(TAG, "Application is busy, skipping")
            NotificationUtils.showBusyToast(this)
            return START_NOT_STICKY
        }

        val info = intent.getParcelableExtra<P2pInfo>("p2p_info") ?: run {
            MyApplication.getInstance().clearBusy()
            return START_NOT_STICKY
        }

        val localTaskId = Random.nextInt()
        val job = scope.launch(Dispatchers.IO) {
            try {
                updateStage(localTaskId, getString(R.string.device), LiveStage.INIT)
                runReceive(info, localTaskId)
                updateStage(localTaskId, "ReCatShare", LiveStage.COMPLETED)
                delay(5000)
            } catch (e: CancelledByUserException) {
                Log.i(TAG, "Cancelled by user")
                notificationManager.notify(
                    Random.nextInt(),
                    createFailedNotification(e)
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to process task", e)
                notificationManager.notify(
                    Random.nextInt(),
                    createFailedNotification(e)
                )
            } finally {
                LiveUpdateCoordinator.clearState("RECEIVER")
                MyApplication.getInstance().clearBusy()
                
                synchronized(currentTaskLock) {
                    currentTaskId = null
                    currentJob = null
                }
                stopSelf()
            }
        }

        synchronized(currentTaskLock) {
            currentTaskId = localTaskId
            currentJob = job
        }


        return START_NOT_STICKY
    }

    private fun createContentValues(file: File): ContentValues {
        val extension = file.extension
        val mimeType = if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else null

        return ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CatShare"
            )
        }
    }

    private fun createNotificationBuilder(@DrawableRes icon: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NotificationUtils.RECEIVER_CHAN_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(icon).setPriority(NotificationCompat.PRIORITY_MAX)
    }

    private fun createCompletedNotification(
        senderName: String, receivedFiles: List<ReceivedFile>, isPartial: Boolean
    ): Notification {
        val style = NotificationCompat.BigTextStyle()
            .bigText(receivedFiles.take(5).joinToString("\n") { it.name })
        val builder =
            createNotificationBuilder(R.drawable.ic_done).setContentTitle(getString(R.string.recv_ok))
                .setSubText(senderName).setAutoCancel(true).setContentText(
                    if (isPartial) {
                        resources.getQuantityString(
                            R.plurals.noti_complete_partial, receivedFiles.size, receivedFiles.size
                        )
                    } else {
                        resources.getQuantityString(
                            R.plurals.noti_complete, receivedFiles.size, receivedFiles.size
                        )
                    }
                ).setStyle(style)

        val intent = if (receivedFiles.size == 1) {
            val rf = receivedFiles.first()
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rf.uri, rf.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(
                    "android.provider.extra.INITIAL_URI",
                    "content://downloads/public_downloads".toUri()
                )
            }
        }
        builder.setContentIntent(
            PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
        )
        return builder.build()
    }

    private fun createFailedNotification(exception: Throwable?): Notification {
        if (AppSettings(this).verbose && exception != null) {
            return createNotificationBuilder(R.drawable.ic_warning)
                .setContentTitle(getString(R.string.recv_fail))
                .setContentText(getString(R.string.expand_for_details))
                .setStyle(NotificationCompat.BigTextStyle().bigText(exception.stackTraceToString()))
                .setAutoCancel(true).build()
        }
        return createNotificationBuilder(R.drawable.ic_warning)
            .setContentTitle(getString(R.string.recv_fail))
            .setContentText(
                if (exception != null && exception is ExceptionWithMessage) {
                    exception.getMessage(this)
                } else if (exception != null && exception is CancelledByUserException) {
                    if (exception.isRemote) {
                        getString(R.string.cancelled_by_user_remote)
                    } else {
                        getString(R.string.cancelled_by_user_local)
                    }
                } else {
                    getString(R.string.noti_send_interrupted)
                }
            )
            .setAutoCancel(true).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runReceive(p2pInfo: P2pInfo, localTaskId: Int) = coroutineScope {
        updateStage(localTaskId, getString(R.string.device), LiveStage.PREPARING)
        val client = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    val sslContext = SSLContext.getInstance("TLSv1.2")
                    val tm = FakeTrustManager()
                    sslContext.init(null, arrayOf(tm), SecureRandom())

                    connectTimeout(3, TimeUnit.SECONDS)
                    connectionPool(
                        ConnectionPool(5, 10, TimeUnit.SECONDS)
                    )
                    sslSocketFactory(sslContext.socketFactory, tm)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }

        val p2pConfig = WifiP2pConfig.Builder()
            .setNetworkName(p2pInfo.ssid)
            .setPassphrase(p2pInfo.psk)
            .build()

        client.use { client ->
            p2pFuture = CompletableDeferred()
            val groupInfo = p2pManager.requestGroupInfo(p2pChannel)
            if (groupInfo != null) {
                Log.i(TAG, "A P2P group already exists, trying to remove")
                p2pManager.removeGroupSuspend(p2pChannel)
            }
            p2pManager.connectSuspend(p2pChannel, p2pConfig)
            try {
                val (wifiP2pInfo, _) = p2pFuture.awaitWithTimeout(
                    Duration.ofSeconds(10), "Waiting for P2P connect", R.string.error_p2p_failed
                )

                val hostPort = "${wifiP2pInfo.groupOwnerAddress.hostAddress}:${p2pInfo.port}"

                val sendRequestFuture = CompletableDeferred<JSONObject>()
                val statusFuture = CompletableDeferred<Pair<Int, String>>()

                var currentFileName: String? = null

                val wsSession = client.webSocketSession("wss://${hostPort}/websocket")

                val downloadJob = async {
                    val sendRequestPayload = sendRequestFuture.awaitWithTimeout(
                        Duration.ofSeconds(5), "Waiting for send request",
                        R.string.err_recv_req_timeout
                    )

                    val taskId = sendRequestPayload.optString("taskId", sendRequestPayload.optString("id"))
                    val senderName = sendRequestPayload.getString("senderName")
                    val senderBrand = if (sendRequestPayload.has("senderBrand")) {
                        sendRequestPayload.getString("senderBrand")
                    } else {
                        val brandId = sendRequestPayload.optInt("senderBrandId", -1)
                        if (brandId != -1) DeviceUtils.deviceNameById(brandId) else getString(R.string.unknown)
                    }
                    val senderDisplayName = if (senderBrand == getString(R.string.unknown)) senderName else "$senderName ($senderBrand)"
                    Log.d("PROTOCOL_PROBE:WS_FRAME", "Sender Info: $senderDisplayName")

                    updateStage(localTaskId, senderDisplayName, LiveStage.HANDSHAKE)

                    val totalSize = sendRequestPayload.getLong("totalSize")
                    val fileCount = sendRequestPayload.getInt("fileCount")
                    val textContent = if (sendRequestPayload.has("catShareText")) {
                        sendRequestPayload.getString("catShareText")
                    } else {
                        null
                    }

                    val thumbPath = sendRequestPayload.optString("thumbnail")
                    if (thumbPath.isNotEmpty()) {
                        val thumbUrl = "https://${hostPort}$thumbPath"
                        Log.d(TAG, "Fetching thumbnail from $thumbUrl")

                        val body = client.get(thumbUrl).bodyAsBytes()
                        BitmapFactory.decodeByteArray(body, 0, body.size)
                    }

                    if (!AppSettings(this@P2pReceiverService).autoAccept) {
                        updateStage(localTaskId, senderName, LiveStage.WAITING_AUTH)

                        val userResponse = withTimeoutOrNull(10000L) {
                            waitForAction(localTaskId)
                        }

                        if (userResponse != true) {
                            wsSession.sendStatusIgnoreException(99, taskId, 3, "user refuse")
                            throw CancelledByUserException(false)
                        }
                    }
                    if (textContent != null) {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.shared_text), textContent))

                        showTextCopiedToast()

                        wsSession.sendStatusIgnoreException(99, taskId, 1, "ok")
                        delay(1000)
                        return@async
                    }

                    val downloadUrl = "https://${hostPort}/download?taskId=${taskId}"

                    val files = client.prepareGet(downloadUrl).execute { downloadRes ->
                        val ist = downloadRes.bodyAsChannel().toInputStream()

                        val progress = ProgressCounter(totalSize) { total, processed ->
                            val percent = (100.0 * processed / total).toInt()
                            updateStage(localTaskId, senderDisplayName, LiveStage.TRANSFERRING, percent, currentFileName)
                        }

                        updateStage(localTaskId, senderDisplayName, LiveStage.FINALIZING)

                        ZipInputStream(ist).use { zipStream ->
                            saveArchive(zipStream, progress) { name ->
                                currentFileName = name
                                updateStage(localTaskId, senderDisplayName, LiveStage.TRANSFERRING, 0, name)
                            }
                        }
                    }

                    if (files.isNotEmpty()) {
                        notificationManager.notify(
                            Random.nextInt(), createCompletedNotification(
                                senderName, files, files.size != fileCount
                            )
                        )
                        wsSession.sendStatusIgnoreException(99, taskId, 1, "ok")
                        delay(1000)
                    } else {
                        throw IllegalStateException("Failed to receive any file")
                    }
                }

                while (true) {
                    val run = select {
                        wsSession.incoming.onReceive { frame ->
                            val text = (frame as? Frame.Text)?.readText()
                                ?: throw IllegalArgumentException("Got non-text frame")
                            val message = WebSocketMessage.fromText(text)
                                ?: throw IllegalArgumentException("Failed to parse message")

                            Log.d("PROTOCOL_PROBE:WS_FRAME", "Incoming: $message")

                            if (message.type != "action") {
                                return@onReceive true
                            }

                            val payload = message.payload ?: return@onReceive true

                            val r = when (message.name.lowercase()) {
                                "versionnegotiation" -> {
                                    val inVersion = payload.optInt("version", 1)
                                    val currentVersion = min(inVersion, 1)

                                    JSONObject()
                                        .put("version", currentVersion)
                                        .put("threadLimit", 5)
                                }

                                "sendrequest" -> {
                                    sendRequestFuture.complete(payload)
                                    null
                                }

                                "status" -> {
                                    statusFuture.complete(
                                        Pair(
                                            payload.optInt("type"), payload.optString("reason")
                                        )
                                    )
                                    null
                                }

                                else -> {
                                    null
                                }
                            }

                            val ack = WebSocketMessage("ack", message.id, message.name, r)
                            Log.d("PROTOCOL_PROBE:WS_FRAME", "Sending Ack: ${ack.toText()}")
                            wsSession.send(Frame.Text(ack.toText()))
                            true
                        }
                        downloadJob.onAwait {
                            false
                        }
                        statusFuture.onAwait { status ->
                            if (status.first == 3 && status.second == "user refuse") {
                                throw CancelledByUserException(true)
                            }
                            throw RuntimeException("Transfer terminated with $status")
                        }
                    }

                    if (!run) {
                        break
                    }
                }
            } finally {
                p2pManager.removeGroup(p2pChannel, null)
                p2pManager.cancelConnect(p2pChannel, null)
            }
        }
    }

    private fun getCustomDownloadDir(): DocumentFile? {
        val settings = AppSettings(this)
        val uriStr = settings.downloadUri ?: return null
        val uri = Uri.parse(uriStr)

        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri.toString() && it.isWritePermission
        }
        if (!hasPermission) {
            Log.w(TAG, "No persisted permission for $uri")
            return null
        }

        return try {
            val df = DocumentFile.fromTreeUri(this, uri)
            if (df?.exists() == true && df.isDirectory) df else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve custom download dir", e)
            null
        }
    }

    private fun saveArchive(
        zipStream: ZipInputStream,
        progress: ProgressCounter,
        onFileStart: (String) -> Unit
    ): List<ReceivedFile> {
        val receivedFiles = mutableListOf<ReceivedFile>()
        var processedSize = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            dalvik.system.ZipPathValidator.setCallback(ZipPathValidatorCallback)
        }

        val customDir = getCustomDownloadDir()

        while (true) {
            val entry = zipStream.nextEntry ?: break
            if (entry.isDirectory) {
                continue
            }

            Log.d(TAG, "Entry ${entry.name}")
            onFileStart(entry.name)

            val entryFile = File(entry.name)
            
            try {
                val (uri, mimeType) = if (customDir != null) {
                    val extension = entryFile.extension
                    val mime = if (extension.isNotEmpty()) {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                    } else "application/octet-stream"
                    
                    val doc = customDir.createFile(mime, entryFile.name)
                        ?: throw RuntimeException("Failed to create file ${entryFile.name} in custom dir")
                    Pair(doc.uri, mime)
                } else {
                    val values = createContentValues(entryFile)
                    val insertedUri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw RuntimeException("Failed to write ${entryFile.name} to media store")
                    Pair(insertedUri, values.getAsString(MediaStore.Downloads.MIME_TYPE))
                }

                try {
                    val os = contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException("Failed to open ${entryFile.name}")
                    val buffer = ByteArray(1024 * 1024 * 4)

                    os.use {
                        while (true) {
                            val readLen = zipStream.read(buffer)
                            if (readLen == -1) {
                                break
                            }
                            os.write(buffer, 0, readLen)

                            processedSize += readLen.toLong()
                            progress.update(processedSize)
                        }
                    }

                    receivedFiles.add(
                        ReceivedFile(
                            entryFile.name,
                            uri,
                            mimeType
                        )
                    )
                } catch (e: Throwable) {
                    if (customDir == null) {
                        contentResolver.delete(uri, null, null)
                    }
                    throw e
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to receive ${entryFile.name}, stopping", e)
                break
            }
        }

        Log.d(TAG, "Received ${receivedFiles.size} files")

        return receivedFiles
    }

    private suspend fun waitForAction(taskId: Int) = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra("taskId", -1) != taskId) {
                    return
                }

                when (intent.action) {
                    ACTION_ACCEPTED -> continuation.resume(true) { _, _, _ -> }
                    ACTION_DISMISSED -> continuation.resume(false) { _, _, _ -> }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_ACCEPTED)
            addAction(ACTION_DISMISSED)
        }
        registerInternalBroadcastReceiver(receiver, filter)

        continuation.invokeOnCancellation {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister waitForAction receiver", e)
            }
        }
    }

    fun cancel(taskId: Int) {
        synchronized(currentTaskLock) {
            if (currentTaskId == taskId) {
                currentJob?.cancel(CancelledByUserException(false))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveUpdateCoordinator.clearState("RECEIVER")
        scope.launch { currentJob?.cancel() }

        if (internalReceiverRegistered) {
            unregisterReceiver(internalReceiver)
        }
    }

    private fun showTextCopiedToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                this@P2pReceiverService,
                R.string.msg_copied_to_clipboard,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        val TAG: String = P2pReceiverService::class.java.simpleName
        fun getIntent(context: Context, p2pInfo: P2pInfo): Intent {
            return Intent(context, P2pReceiverService::class.java).apply {
                putExtra("p2p_info", p2pInfo)
            }
        }

        private val ACTION_DISMISSED = "${BuildConfig.APPLICATION_ID}.NOTIFICATION_DISMISSED"
        private val ACTION_ACCEPTED = "${BuildConfig.APPLICATION_ID}.NOTIFICATION_ACCEPTED"
        private val ACTION_CANCEL_RECEIVING = "${BuildConfig.APPLICATION_ID}.CANCEL_RECEIVING"
    }
}
