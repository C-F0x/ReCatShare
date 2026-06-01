package moe.reimu.catshare.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.serialization.json.Json
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.BleSecurity
import moe.reimu.catshare.BuildConfig
import moe.reimu.catshare.R
import moe.reimu.catshare.models.DeviceInfo
import moe.reimu.catshare.models.LiveUpdatePriority
import moe.reimu.catshare.models.LiveUpdateState
import moe.reimu.catshare.models.P2pInfo
import moe.reimu.catshare.utils.BleUtils
import moe.reimu.catshare.utils.DeviceUtils
import moe.reimu.catshare.utils.JsonWithUnknownKeys
import moe.reimu.catshare.utils.LiveUpdateCoordinator
import moe.reimu.catshare.utils.NotificationUtils
import moe.reimu.catshare.utils.ServiceState
import moe.reimu.catshare.utils.ShizukuUtils
import moe.reimu.catshare.utils.checkBluetoothPermissions
import moe.reimu.catshare.utils.registerInternalBroadcastReceiver
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class GattServerService : Service() {
    private lateinit var btManager: BluetoothManager
    private var btAdvertiser: BluetoothLeAdvertiser? = null

    private var advertisingSet: AdvertisingSet? = null

    private val localDeviceInfoLock = Any()
    private var localDeviceInfo = DeviceInfo(
        0, BleSecurity.getEncodedPublicKey(), "02:00:00:00:00:00", BuildConfig.VERSION_CODE
    )
    private var localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()

    private val shutdownHandler = Handler(Looper.getMainLooper())
    private var receiveCount = 0
    private var startTime = 0L

    private var isFinishing = false

    private var isBusy = false

    private val updateTicker = object : Runnable {
        override fun run() {
            broadcastState()
            shutdownHandler.postDelayed(this, 1000)
        }
    }

    private fun broadcastState() {
        val settings = AppSettings(this)
        var progress = 0f
        var progressText = ""
        var targetWhen = 0L

        when (settings.autoShutdownMode) {
                1 -> {
                    val totalMs = settings.autoShutdownSeconds * 1000L
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val remainingMs = max(0L, totalMs - elapsedMs)
                    progress = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
                    
                    // Add 999ms offset to make it feel like "ceiling" division
                    // So 20m 20s (20000ms) shows as 20:20 at the very start
                    val displayRemainingMs = if (remainingMs > 0) remainingMs + 999 else 0
                    val remainingTotalSec = displayRemainingMs / 1000
                    val h = remainingTotalSec / 3600
                    val m = (remainingTotalSec % 3600) / 60
                    val s = remainingTotalSec % 60
                    progressText = if (h > 0) {
                        String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
                    } else {
                        String.format(java.util.Locale.US, "%02d:%02d", m, s)
                    }
                    targetWhen = startTime + totalMs

                    if (remainingMs <= 0) {
                        triggerFinishing()
                        return
                    }
                }
                2 -> {
                    val total = settings.autoShutdownCount
                    progress = (receiveCount.toFloat() / total).coerceIn(0f, 1f)
                    progressText = "${max(0, total - receiveCount)} ${getString(R.string.auto_shutdown_count_unit)}"
                }
            }

        sendBroadcast(ServiceState.getUpdateIntent(true, progress, progressText, isFinishing))

        val state = LiveUpdateState(
            title = getString(R.string.noti_receiver_title),
            content = if (progressText.isNotEmpty()) 
                "${getString(R.string.discoverable_desc)}  •  $progressText" 
                else getString(R.string.discoverable_desc),
            progress = if (settings.autoShutdownMode != 0) (progress * 100).toInt() else -1,
            shortCriticalText = if (settings.autoShutdownMode == 2) progressText else null,
            priority = LiveUpdatePriority.STANDBY,
            cancelIntent = PendingIntent.getBroadcast(
                this, 0, ServiceState.getStopIntent(), PendingIntent.FLAG_IMMUTABLE
            ),
            channelId = NotificationUtils.RECEIVER_FG_CHAN_ID,
            smallIcon = R.drawable.ic_bluetooth_searching,
            usesChronometer = settings.autoShutdownMode == 1,
            whenTime = targetWhen,
            chronometerCountDown = true
        )
        LiveUpdateCoordinator.publishState("GATT", state)
        updateForeground()
    }

    private fun updateForeground() {
        startForeground(
            NotificationUtils.ID_LIVE_UPDATE,
            NotificationUtils.getCurrentLiveNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun triggerFinishing() {
        if (isFinishing) return
        isFinishing = true
        shutdownHandler.removeCallbacks(updateTicker)
        stopSelf()
    }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_QUERY_RECEIVER_STATE -> {
                    broadcastState()
                }
                ServiceState.ACTION_STOP_SERVICE -> {
                    Log.i(TAG, "Received ACTION_STOP_SERVICE")
                    stopSelf()
                }

                ServiceState.ACTION_BUSY_CHANGED -> {
                    isBusy = intent.getBooleanExtra("busy", false)
                    if (isBusy) {
                        shutdownHandler.removeCallbacks(updateTicker)
                    } else {
                        if (isFinishing) return

                        val settings = AppSettings(this@GattServerService)
                        if (settings.autoShutdownMode == 1 || settings.autoShutdownMode == 2) {
                            shutdownHandler.post(updateTicker)
                        } else {
                            broadcastState()
                        }
                    }
                }
            }
        }
    }

    private var internalReceiverRegistered = false

    private val advSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?, txPower: Int, status: Int
        ) {
            if (status == ADVERTISE_SUCCESS) {
                this@GattServerService.advertisingSet = advertisingSet
            } else {
                Log.e(TAG, "Advertising failed: $status")
            }
        }
    }

    private var gattServer: BluetoothGattServer? = null

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        private val writeRequests =
            ConcurrentHashMap<Pair<BluetoothDevice, Int>, Pair<ByteArray, Int>>()

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != BleUtils.CHAR_STATUS_UUID) {
                gattServer?.sendResponse(device, requestId, 257, 0, null)
                return
            }

            val data = synchronized(localDeviceInfoLock) {
                if (offset < localDeviceStatusBytes.size) {
                    localDeviceStatusBytes.copyOfRange(offset, localDeviceStatusBytes.size)
                } else {
                    null
                }
            }

            gattServer?.sendResponse(device, requestId, 0, 0, data)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != BleUtils.CHAR_P2P_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 257, 0, null)
                }
                return
            }

            val key = Pair(device, requestId)

            val writeReq = writeRequests.getOrPut(key) {
                Pair(ByteArray(1024), 0)
            }

            System.arraycopy(value, 0, writeReq.first, offset, value.size)
            val newLength = max(writeReq.second, offset + value.size)

            val data = if (preparedWrite) {
                writeRequests[key] = writeReq.copy(second = newLength)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 0, 0, null)
                }
                return
            } else {
                writeRequests.remove(key)
                writeReq.first.copyOfRange(0, newLength)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, 0, 0, null)
            }

            var p2pInfo: P2pInfo = JsonWithUnknownKeys.decodeFromString(data.decodeToString())
            Log.d("PROTOCOL_PROBE:BLE_HANDSHAKE", "Received Raw P2P Info: ${data.decodeToString()}")
            
            val ecKey = p2pInfo.key
            if (ecKey != null) {
                val cipher = BleSecurity.deriveSessionKey(ecKey)
                p2pInfo = P2pInfo(
                    id = BleUtils.getSenderId(),
                    ssid = cipher.decrypt(p2pInfo.ssid),
                    psk = cipher.decrypt(p2pInfo.psk),
                    mac = cipher.decrypt(p2pInfo.mac),
                    port = p2pInfo.port,
                    key = null,
                    catShare = BuildConfig.VERSION_CODE,
                )
                Log.d("PROTOCOL_PROBE:BLE_HANDSHAKE", "Decrypted P2P Info: $p2pInfo")
            }

            LiveUpdateCoordinator.clearState("GATT")
            startService(P2pReceiverService.getIntent(this@GattServerService, p2pInfo))

            val settings = AppSettings(this@GattServerService)
            if (settings.autoShutdownMode == 2) {
                receiveCount++
                if (receiveCount >= settings.autoShutdownCount) {
                    triggerFinishing()
                } else {
                    broadcastState()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startTime = System.currentTimeMillis()

        if (!checkBluetoothPermissions()) {
            stopSelf()
            return
        }

        try {
            btManager = getSystemService(BluetoothManager::class.java)
            val btAdapter = btManager.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                throw IllegalStateException("Bluetooth not enabled")
            }
            btAdvertiser = btAdapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BT", e)
            NotificationUtils.showBluetoothToast(this)
            stopSelf()
            return
        }

        ShizukuUtils.getMacAddress(this, "p2p0") {
            if (it != null) {
                updateMacAddress(it)
            }
        }

        startAdv()

        val filter = IntentFilter().apply {
            addAction(ServiceState.ACTION_QUERY_RECEIVER_STATE)
            addAction(ServiceState.ACTION_STOP_SERVICE)
            addAction(ServiceState.ACTION_BUSY_CHANGED)
        }
        registerInternalBroadcastReceiver(internalReceiver, filter)
        internalReceiverRegistered = true

        sendBroadcast(ServiceState.getUpdateIntent(true))

        val settings = AppSettings(this)
        if (settings.autoShutdownMode == 1) {
            shutdownHandler.post(updateTicker)
        } else {
            broadcastState()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startAdv() {
        val advertiser = btAdvertiser ?: return

        val brandId = DeviceUtils.getLocalBrandId()
        val bleBrandId = if (brandId == 114514) 114 else brandId
        val supports5Ghz = 1 // Assume true for now
        
        // Ensure values are within byte range and masked to prevent String.format overflow
        val b1 = supports5Ghz and 0xFF
        val b2 = bleBrandId and 0xFF

        val advData = AdvertiseData.Builder().apply {
            addServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID))
            // The scanner expects brand info at index 2 and 3 of the 16-byte UUID array
            // Index 0 and 1 must be 0000 to be recognized as a 16-bit UUID and save space
            addServiceData(
                ParcelUuid.fromString(
                    String.format(
                        "0000%02x%02x-0000-1000-8000-00805f9b34fb",
                        b1, b2
                    )
                ), 
                ByteArray(6).apply {
                    // Scanner expects data size 6
                    System.arraycopy(BleUtils.RANDOM_DATA, 0, this, 0, min(BleUtils.RANDOM_DATA.size, 6))
                }
            )
        }.build()
        val scanRespData = AdvertiseData.Builder().apply {
            val data = ByteArray(27)
            System.arraycopy(ByteArray(8), 0, data, 0, 8)
            System.arraycopy(BleUtils.RANDOM_DATA, 0, data, 8, 2)

            val name = AppSettings(this@GattServerService).deviceName
            var nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 15) {
                var str = String(nameBytes.copyOf(15), Charsets.UTF_8)
                var length = str.length - 1

                while (length >= 0 && !name.startsWith(str)) {
                    str = str.substring(0, length)
                    length -= 1
                }

                nameBytes = (str + "\t").toByteArray(Charsets.UTF_8)
            }
            System.arraycopy(nameBytes, 0, data, 10, min(nameBytes.size, 16))

            data[26] = 1

            addServiceData(ParcelUuid.fromString("0000ffff-0000-1000-8000-00805f9b34fb"), data)
        }.build()

        val params = AdvertisingSetParameters.Builder().apply {
            setLegacyMode(true)
            setConnectable(true)
            setScannable(true)
            setInterval(160)
            setTxPowerLevel(1)
        }.build()

        try {
            advertiser.startAdvertisingSet(
                params, advData, scanRespData, null, null, 0, 0, advSetCallback
            )

            gattServer = btManager.openGattServer(this, gattServerCallback).apply {
                addService(buildGattService())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Got SecurityException when trying to advertise", e)
            stopSelf()
        }
    }

    private fun buildGattService(): BluetoothGattService {
        val svc = BluetoothGattService(
            BleUtils.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_STATUS_UUID, 10, 17)
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_P2P_UUID, 10, 17)
        )
        return svc
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        shutdownHandler.removeCallbacksAndMessages(null)

        if (internalReceiverRegistered) {
            unregisterReceiver(internalReceiver)
        }

        LiveUpdateCoordinator.clearState("GATT")
        sendBroadcast(ServiceState.getUpdateIntent(false))

        try {
            advertisingSet?.run {
                btAdvertiser?.stopAdvertisingSet(advSetCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
        advertisingSet = null

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop GATT server", e)
        }
        gattServer = null
    }

    private fun updateMacAddress(mac: String) {
        Log.i(TAG, "Updating local MAC address to $mac")
        synchronized(localDeviceInfoLock) {
            localDeviceInfo = DeviceInfo(
                state = localDeviceInfo.state,
                mac = mac,
                key = localDeviceInfo.key,
                catShare = BuildConfig.VERSION_CODE,
            )
            localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()
        }
    }

    companion object {
        val TAG: String = GattServerService::class.java.simpleName
        fun getIntent(context: Context): Intent {
            return Intent(context, GattServerService::class.java)
        }

        fun start(context: Context) {
            context.startService(getIntent(context))
        }

        fun stop(context: Context) {
            context.stopService(getIntent(context))
        }
    }
}
