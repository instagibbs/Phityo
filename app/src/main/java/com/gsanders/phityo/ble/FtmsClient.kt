package com.gsanders.phityo.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.gsanders.phityo.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "Phityo.Ble"

private val FITNESS_MACHINE_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
private val MACHINE_FEATURE_CHAR:    UUID = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")
private val TREADMILL_DATA_CHAR:     UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
private val TRAINING_STATUS_CHAR:    UUID = UUID.fromString("00002ad3-0000-1000-8000-00805f9b34fb")
private val CONTROL_POINT_CHAR:      UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
private val CCCD:                    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Trailviber-style proprietary BLE-UART used for actual motor control.
private val VENDOR_SERVICE:  UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val VENDOR_NOTIFY:   UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val VENDOR_WRITE:    UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

private const val KM_PER_MILE = 1.609344
private const val VENDOR_POLL_INTERVAL_MS = 500L

// Target Setting Features bitmap (second uint32 of 0x2ACC).
private const val TARGET_SPEED_BIT   = 1 shl 0
private const val TARGET_INCLINE_BIT = 1 shl 1

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class MachineFeatures(
    val supportsSpeedTarget: Boolean,
    val supportsInclineTarget: Boolean,
    val rawFeaturesHex: String,
)

@SuppressLint("MissingPermission")
class FtmsClient(
    private val ctx: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _data = MutableStateFlow(TreadmillData())
    val data: StateFlow<TreadmillData> = _data.asStateFlow()

    private val _trainingStatus = MutableStateFlow(TrainingStatus.Unknown)
    val trainingStatus: StateFlow<TrainingStatus> = _trainingStatus.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _deviceMac = MutableStateFlow<String?>(null)
    val deviceMac: StateFlow<String?> = _deviceMac.asStateFlow()

    private val _hasControl = MutableStateFlow(false)
    val hasControl: StateFlow<Boolean> = _hasControl.asStateFlow()

    /** Human-readable status for the control-request dance. */
    private val _controlDiag = MutableStateFlow<String?>(null)
    val controlDiag: StateFlow<String?> = _controlDiag.asStateFlow()

    /** Hex of the most recent raw control-point indication, or null if nothing received. */
    private val _lastControlHex = MutableStateFlow<String?>(null)
    val lastControlHex: StateFlow<String?> = _lastControlHex.asStateFlow()

    private val _features = MutableStateFlow<MachineFeatures?>(null)
    val features: StateFlow<MachineFeatures?> = _features.asStateFlow()

    /**
     * Vendor-protocol state byte from the 0x51 poll response:
     * 0x00 idle, 0x02 countdown, 0x03 running, 0x04 stopping/cooling.
     * Defaults to 0 (idle) before we've received a poll.
     */
    private val _vendorState = MutableStateFlow(0)
    val vendorState: StateFlow<Int> = _vendorState.asStateFlow()

    private val adapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var vendorWrite: BluetoothGattCharacteristic? = null

    // Cached target state — the proprietary "set target" command carries speed
    // AND incline in a single frame, so we must remember both to send either.
    private var targetSpeedTenths: Int = 7   // 0.7 mph min
    private var targetInclinePct: Int = 0

    // GATT is strictly serial: only one pending write/descriptor at a time.
    private val writeQueue: ArrayDeque<() -> Unit> = ArrayDeque()
    private var writeBusy = false

    private var autoReconnect = true
    private var reconnectJob: Job? = null
    private var controlRequestJob: Job? = null
    private var vendorPollJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adapter?.bluetoothLeScanner?.stopScan(this)
            connect(result.device)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connState=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.CONNECTING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handleDisconnect(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "servicesDiscovered status=$status")
            val service = g.getService(FITNESS_MACHINE_SERVICE) ?: run {
                g.disconnect(); return
            }
            val feature = service.getCharacteristic(MACHINE_FEATURE_CHAR)
            val data = service.getCharacteristic(TREADMILL_DATA_CHAR)
            val ts   = service.getCharacteristic(TRAINING_STATUS_CHAR)
            val cp   = service.getCharacteristic(CONTROL_POINT_CHAR)
            controlPoint = cp

            if (feature != null) enqueueRead(g, feature)
            if (data != null)
                enqueueSubscribe(g, data, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (ts != null)
                enqueueSubscribe(g, ts, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (cp != null)
                enqueueSubscribe(g, cp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

            // Vendor BLE-UART used by the stock app for actual motor control.
            val vendorSvc = g.getService(VENDOR_SERVICE)
            val vendorN = vendorSvc?.getCharacteristic(VENDOR_NOTIFY)
            val vendorW = vendorSvc?.getCharacteristic(VENDOR_WRITE)
            vendorWrite = vendorW
            if (vendorN != null)
                enqueueSubscribe(g, vendorN, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (vendorW != null) {
                // Replay the Fityo session-init sequence, then start polling.
                enqueueVendorWrite(ProprietaryControl.HANDSHAKE_BODY)
                enqueueVendorWrite(ProprietaryControl.QUERY_A_BODY)
                enqueueVendorWrite(ProprietaryControl.QUERY_B_BODY)
                startVendorPollLoop()
            }

            _state.value = ConnectionState.CONNECTED
            scope.launch { settings.setLastDeviceMac(g.device.address) }
            _deviceMac.value = g.device.address

            if (cp != null) startControlRequestLoop()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            handleReadValue(ch, value, status)
            stepQueue()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleReadValue(ch, ch.value ?: ByteArray(0), status)
            stepQueue()
        }

        private fun handleReadValue(ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (ch.uuid != MACHINE_FEATURE_CHAR || status != BluetoothGatt.GATT_SUCCESS) return
            // 0x2ACC layout: two little-endian uint32. First = machine feature,
            // second = target-setting feature. We only care about the second for
            // "can I actually tell this thing to change speed/incline?".
            if (value.size < 8) return
            val target = (value[4].toInt() and 0xFF) or
                ((value[5].toInt() and 0xFF) shl 8) or
                ((value[6].toInt() and 0xFF) shl 16) or
                ((value[7].toInt() and 0xFF) shl 24)
            _features.value = MachineFeatures(
                supportsSpeedTarget = (target and TARGET_SPEED_BIT) != 0,
                supportsInclineTarget = (target and TARGET_INCLINE_BIT) != 0,
                rawFeaturesHex = value.joinToString(" ") { "%02X".format(it) },
            )
            Log.d(TAG, "features target=0x%08X speed=${_features.value?.supportsSpeedTarget} incline=${_features.value?.supportsInclineTarget}".format(target))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) = handleValue(ch, value)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleValue(ch, ch.value)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            Log.d(TAG, "descWrite char=${descriptor.characteristic.uuid} status=$status")
            stepQueue()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            Log.d(TAG, "charWrite ${ch.uuid} status=$status")
            stepQueue()
        }

        private fun handleValue(ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
                TREADMILL_DATA_CHAR ->
                    FtmsParser.parseTreadmillData(value)?.let {
                        _data.value = merge(_data.value, it)
                    }
                TRAINING_STATUS_CHAR ->
                    FtmsParser.parseTrainingStatus(value)?.let { _trainingStatus.value = it }
                CONTROL_POINT_CHAR -> handleControlIndication(value)
                VENDOR_NOTIFY -> handleVendorNotification(value)
            }
        }
    }

    // ---------- public API ----------

    fun startScan() {
        if (_state.value != ConnectionState.DISCONNECTED) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        autoReconnect = true
        _state.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FITNESS_MACHINE_SERVICE))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), scanSettings, scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value == ConnectionState.SCANNING) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    fun autoReconnectIfKnown() {
        scope.launch {
            val mac = settings.lastDeviceMac.first() ?: return@launch
            connectByMac(mac)
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        controlRequestJob?.cancel()
        vendorPollJob?.cancel()
        gatt?.disconnect()
    }

    fun forget() {
        disconnect()
        scope.launch { settings.setLastDeviceMac(null) }
        _deviceMac.value = null
        _deviceName.value = null
    }

    /** User-initiated retry — useful if the auto-retry timed out. */
    fun retryControlRequest() {
        val g = gatt ?: return
        val cp = controlPoint ?: return
        if (_hasControl.value) return
        _controlDiag.value = "Retrying control request…"
        enqueueWrite(g, cp, FtmsControl.requestControl())
        startControlRequestLoop(fromRetry = true)
    }

    // Control commands — routed through the vendor BLE-UART since this
    // treadmill silently ignores writes to the FTMS Control Point despite
    // advertising support for them.
    fun start() {
        enqueueVendorWrite(ProprietaryControl.START_BODY)
    }

    fun stop() {
        enqueueVendorWrite(ProprietaryControl.STOP_BODY)
    }

    fun pause() = stop()

    /**
     * Start the belt and, once it finishes the firmware's 3-2-1 countdown
     * and enters running state 0x03, push the user's previous target
     * (speed in 0.1-mph units, incline in whole %). Sending the target
     * during state 0x02 (countdown) is silently ignored on this firmware,
     * so we have to wait for the transition.
     */
    fun startWithTargets(speedTenths: Int, inclinePct: Int) {
        targetSpeedTenths = speedTenths.coerceIn(0, 255)
        targetInclinePct = inclinePct.coerceIn(0, 255)
        enqueueVendorWrite(ProprietaryControl.START_BODY)
        scope.launch {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                _vendorState.value != 0x03
            ) {
                delay(100)
            }
            sendVendorTarget()
        }
    }

    /** Set target speed. Value is in km/h to match the rest of the codebase; converted to 0.1 mph for the vendor frame. */
    fun setSpeed(kmh: Double) {
        val mph = kmh / KM_PER_MILE
        targetSpeedTenths = (mph * 10).toInt().coerceIn(0, 255)
        sendVendorTarget()
    }

    fun setIncline(percent: Double) {
        targetInclinePct = percent.toInt().coerceIn(0, 255)
        sendVendorTarget()
    }

    /**
     * Nudge target speed by one tenth-mph step in the given [direction]
     * (+1 or -1). Steps from the cached target, so successive rapid presses
     * accumulate correctly even before the belt catches up.
     */
    fun bumpSpeed(direction: Int) {
        targetSpeedTenths = (targetSpeedTenths + direction).coerceIn(0, 255)
        sendVendorTarget()
    }

    fun bumpIncline(direction: Int) {
        targetInclinePct = (targetInclinePct + direction).coerceIn(0, 255)
        sendVendorTarget()
    }

    private fun sendVendorTarget() {
        enqueueVendorWrite(
            ProprietaryControl.setTargetBody(targetSpeedTenths, targetInclinePct)
        )
        val speed = targetSpeedTenths
        val incline = targetInclinePct
        scope.launch { settings.setLastTargets(speed, incline) }
    }

    private fun enqueueVendorWrite(body: ByteArray) {
        val g = gatt ?: return
        val ch = vendorWrite ?: return
        val framed = ProprietaryControl.frame(body)
        enqueue {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, framed, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                ch.value = framed
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }
    }

    private fun startVendorPollLoop() {
        vendorPollJob?.cancel()
        vendorPollJob = scope.launch {
            // Small initial delay so handshake + queries land first.
            delay(300)
            while (true) {
                if (gatt == null || vendorWrite == null) return@launch
                enqueueVendorWrite(ProprietaryControl.POLL_BODY)
                delay(VENDOR_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleVendorNotification(value: ByteArray) {
        val body = ProprietaryControl.parseFrame(value) ?: return
        if (body.isEmpty()) return
        val opcode = body[0].toInt() and 0xFF
        Log.d(TAG, "vendor RX body=${body.joinToString(" ") { "%02X".format(it) }}")
        when (opcode) {
            0x51 -> {
                // Keep the echo-driven target cache fresh: when the treadmill
                // reports the current state, seed our targets from it so the
                // next +/- press steps from reality rather than our stale
                // assumption. Only do this before the user has issued a
                // target of their own.
                ProprietaryControl.parseStatus(body)?.let { s ->
                    _vendorState.value = s.state
                    if (targetSpeedTenths == 7 && targetInclinePct == 0) {
                        targetSpeedTenths = s.speedMphTenths
                        targetInclinePct = s.inclinePct
                    }
                }
            }
            // 0x53 replies are the treadmill's current-state snapshot at the
            // moment of receipt, not a strict echo — the motor hasn't yet
            // moved to the target we just asked for. If we overwrote our
            // cache from these, rapid consecutive +/- presses would stall
            // at +/-1 because each reply reverts the cache before the next
            // step. So we do nothing here and let our cache track intent.
        }
    }

    // ---------- internals ----------

    private fun connect(device: BluetoothDevice) {
        _deviceName.value = device.name
        _deviceMac.value = device.address
        gatt = device.connectGatt(ctx, false, gattCallback)
    }

    private fun connectByMac(mac: String) {
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull() ?: return
        _state.value = ConnectionState.CONNECTING
        autoReconnect = true
        connect(dev)
    }

    private fun handleDisconnect(g: BluetoothGatt) {
        _state.value = ConnectionState.DISCONNECTED
        _hasControl.value = false
        _controlDiag.value = null
        _lastControlHex.value = null
        _features.value = null
        _data.value = TreadmillData()
        _trainingStatus.value = TrainingStatus.Unknown
        synchronized(writeQueue) {
            writeQueue.clear()
            writeBusy = false
        }
        controlRequestJob?.cancel()
        vendorPollJob?.cancel()
        g.close()
        gatt = null
        controlPoint = null
        vendorWrite = null
        targetSpeedTenths = 7
        targetInclinePct = 0
        _vendorState.value = 0

        if (autoReconnect) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                val mac = _deviceMac.value ?: settings.lastDeviceMac.first()
                if (mac != null) {
                    var delayMs = 2_000L
                    while (autoReconnect && _state.value == ConnectionState.DISCONNECTED) {
                        delay(delayMs)
                        if (!autoReconnect) return@launch
                        connectByMac(mac)
                        delayMs = (delayMs * 2).coerceAtMost(30_000L)
                        delay(5_000L)
                    }
                }
            }
        }
    }

    private fun writeControl(payload: ByteArray) {
        val g = gatt ?: return
        val cp = controlPoint ?: return
        if (!_hasControl.value) {
            _controlDiag.value = "Tried to send command but control not yet granted."
            return
        }
        enqueueWrite(g, cp, payload)
    }

    private fun startControlRequestLoop(fromRetry: Boolean = false) {
        controlRequestJob?.cancel()
        controlRequestJob = scope.launch {
            val attempts = if (fromRetry) 1 else 3
            for (attempt in 1..attempts) {
                val g = gatt ?: return@launch
                val cp = controlPoint ?: return@launch
                if (_hasControl.value) return@launch
                _controlDiag.value = "Requesting control (attempt $attempt/$attempts)…"
                enqueueWrite(g, cp, FtmsControl.requestControl())
                // Wait for an indication to flip hasControl true, or for timeout.
                repeat(30) {
                    delay(100)
                    if (_hasControl.value) return@launch
                }
            }
            if (!_hasControl.value) {
                // Optimistic fallback: some FTMS implementations (commonly on
                // cheaper/OEM modules) accept Control Point writes but never
                // send the standard indication ack. Enable the buttons anyway
                // — worst case the user hits Start and nothing happens, but
                // usually the writes do work.
                _hasControl.value = true
                _controlDiag.value =
                    "No ack received — treadmill likely doesn't send indications. " +
                        "Control buttons enabled optimistically; try them and see if the treadmill responds."
            }
        }
    }

    private fun handleControlIndication(value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "controlPoint indication: $hex")
        _lastControlHex.value = hex

        if (value.size >= 3 && (value[0].toInt() and 0xFF) == 0x80) {
            val reqOp = value[1].toInt() and 0xFF
            val result = value[2].toInt() and 0xFF
            when (reqOp) {
                FtmsControl.OP_REQUEST_CONTROL -> {
                    if (result == 0x01) {
                        _hasControl.value = true
                        _controlDiag.value = "Control granted"
                    } else {
                        _controlDiag.value = "Control denied (code 0x%02X)".format(result)
                    }
                }
                else -> {
                    _controlDiag.value = "Response to op 0x%02X: code 0x%02X".format(reqOp, result)
                }
            }
        } else {
            _controlDiag.value = "Unexpected control payload: $hex"
        }
    }

    private fun enqueueRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        enqueue { g.readCharacteristic(ch) }
    }

    private fun enqueueSubscribe(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val d = ch.getDescriptor(CCCD) ?: return
        enqueue {
            g.setCharacteristicNotification(ch, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value)
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    private fun enqueueWrite(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        payload: ByteArray,
    ) {
        enqueue {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = payload
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }
    }

    private fun enqueue(op: () -> Unit) {
        synchronized(writeQueue) {
            writeQueue.addLast(op)
            if (!writeBusy) stepQueueLocked()
        }
    }

    private fun stepQueue() {
        synchronized(writeQueue) { stepQueueLocked() }
    }

    private fun stepQueueLocked() {
        val next = writeQueue.pollFirst()
        if (next == null) {
            writeBusy = false
        } else {
            writeBusy = true
            try { next.invoke() } catch (_: Throwable) { writeBusy = false }
        }
    }

    private fun merge(old: TreadmillData, new: TreadmillData) = TreadmillData(
        speedKmh = new.speedKmh ?: old.speedKmh,
        distanceM = new.distanceM ?: old.distanceM,
        inclinePct = new.inclinePct ?: old.inclinePct,
        totalKcal = new.totalKcal ?: old.totalKcal,
        elapsedSec = new.elapsedSec ?: old.elapsedSec,
        heartRateBpm = new.heartRateBpm ?: old.heartRateBpm,
    )
}
