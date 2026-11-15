package de.mischpult.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * USB and Bluetooth MIDI controllers: discovery, connection, MIDI learn and
 * mapping storage. Messages are decoded by [MidiMapper] and handed to
 * [dispatch] on the main thread.
 */
class MidiController(
    private val context: Context,
    private val dispatch: (MidiCommand) -> Unit,
    /** Whether the LED of a mapped button should be lit; null = not an LED (faders, jog). */
    private val ledState: (MidiAction) -> Boolean?,
) {
    data class Device(val id: String, val name: String, val bluetooth: Boolean, val connected: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            context.getSystemService(MidiManager::class.java)
        } else null
    val supported: Boolean get() = manager != null

    private val mapFile = File(context.filesDir, "midi_map.json")
    private val prefs = context.getSharedPreferences("midi", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _bindings = MutableStateFlow<Map<MidiAction, MidiBinding>>(loadMap())
    val bindings: StateFlow<Map<MidiAction, MidiBinding>> = _bindings.asStateFlow()
    val mapper: MidiMapper = MidiMapper(_bindings.value).also { m ->
        m.onChanged = { map -> saveMap(map); _bindings.value = map }
    }
    private val _learning = MutableStateFlow<MidiAction?>(null)
    val learning: StateFlow<MidiAction?> = _learning.asStateFlow()
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    /** Last message seen, for the "is it talking?" readout. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    /** Connected devices: what they send us (output ports) and what we send them (input ports). */
    private class Connection(val device: MidiDevice, val outputs: List<MidiOutputPort>, val inputs: List<MidiInputPort>)
    private val open = HashMap<String, Connection>()

    // --- LED feedback: the controller's buttons show the app's state ---------------
    private val leds = LedFeedback(prefs.getInt(LED_VALUE, 127))
    private val _ledValue = MutableStateFlow(leds.onValue)
    /** Velocity/value that lights an LED (127 suits most controllers; some use 1 or a colour). */
    val ledValue: StateFlow<Int> = _ledValue.asStateFlow()

    fun setLedValue(v: Int) {
        leds.onValue = v
        prefs.edit().putInt(LED_VALUE, v).apply()
        _ledValue.value = v
        leds.reset() // repaint with the new value
    }

    /** Runs only while a controller is connected; nothing to light otherwise. */
    private var feedbackRunning = false
    private val feedback = object : Runnable {
        override fun run() {
            if (open.isEmpty()) {
                feedbackRunning = false
                return
            }
            val inputs = open.values.flatMap { it.inputs }
            if (inputs.isNotEmpty()) send(inputs, leds.update(_bindings.value, ledState))
            main.postDelayed(this, LED_INTERVAL_MS)
        }
    }

    private fun startFeedback() {
        if (feedbackRunning) return
        feedbackRunning = true
        main.post(feedback)
    }

    private fun send(ports: List<MidiInputPort>, messages: List<ByteArray>) {
        for (msg in messages) for (port in ports) {
            runCatching { port.send(msg, 0, msg.size) }
        }
    }
    private val bluetoothFound = HashMap<String, BluetoothDevice>()

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val events = mapper.parse(msg, offset, count)
            main.post {
                for (e in events) {
                    _lastEvent.value = "${e.binding} = ${e.value}"
                    val command = mapper.handle(e)
                    _learning.value = mapper.learning
                    command?.let(dispatch)
                }
            }
        }
    }

    init {
        manager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                refresh()
                // Plugged back in at the gig: reconnect to the controller used last time
                if (nameOf(device) == prefs.getString(LAST_DEVICE, null)) connect(device)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                close(idOf(device))
                refresh()
            }
        }, main)
        refresh()
        val last = prefs.getString(LAST_DEVICE, null)
        allInfos().firstOrNull { nameOf(it) == last }?.let { connect(it) }
    }

    // --- learning / mapping ---------------------------------------------------------

    fun learn(action: MidiAction?) {
        mapper.learning = action
        _learning.value = action
    }

    fun clear(action: MidiAction) = mapper.clear(action)
    fun clearAll() = mapper.clearAll()

    private fun loadMap(): Map<MidiAction, MidiBinding> = runCatching {
        json.decodeFromString<Map<String, MidiBinding>>(mapFile.readText())
            .mapNotNull { (k, v) -> runCatching { MidiAction.valueOf(k) }.getOrNull()?.let { it to v } }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun saveMap(map: Map<MidiAction, MidiBinding>) {
        val tmp = File(mapFile.parentFile, mapFile.name + ".tmp")
        tmp.writeText(json.encodeToString(map.mapKeys { it.key.name }))
        tmp.renameTo(mapFile)
    }

    // --- devices --------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun allInfos(): List<MidiDeviceInfo> {
        val m = manager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) {
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else m.devices.toList()
    }

    private fun idOf(info: MidiDeviceInfo) = "midi:${info.id}"
    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"

    private fun refresh() {
        val wired = allInfos().filter { it.outputPortCount > 0 }.map {
            Device(idOf(it), nameOf(it), it.type == MidiDeviceInfo.TYPE_BLUETOOTH, idOf(it) in open)
        }
        val ble = bluetoothFound.filterKeys { key -> wired.none { it.id == key } }.map { (key, dev) ->
            Device(key, bluetoothName(dev), true, key in open)
        }
        _devices.value = wired + ble
    }

    fun connect(id: String) {
        allInfos().firstOrNull { idOf(it) == id }?.let { connect(it); return }
        bluetoothFound[id]?.let { connectBluetooth(id, it) }
    }

    private fun connect(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (idOf(info) in open) return
        m.openDevice(info, { device ->
            if (device == null) return@openDevice
            attach(idOf(info), nameOf(info), device)
        }, main)
    }

    @SuppressLint("MissingPermission") // granted before scanning; see the MIDI screen
    private fun connectBluetooth(id: String, device: BluetoothDevice) {
        val m = manager ?: return
        m.openBluetoothDevice(device, { midi ->
            if (midi == null) return@openBluetoothDevice
            attach(id, bluetoothName(device), midi)
        }, main)
    }

    private fun attach(id: String, name: String, device: MidiDevice) {
        // A controller's *output* ports are what it sends to us; its input ports take LED updates
        val ports = (0 until device.info.outputPortCount).mapNotNull { device.openOutputPort(it) }
        ports.forEach { it.connect(receiver) }
        val inputs = (0 until device.info.inputPortCount).mapNotNull { device.openInputPort(it) }
        open[id] = Connection(device, ports, inputs)
        leds.reset() // a freshly connected controller shows nothing yet: send the full state
        startFeedback()
        prefs.edit().putString(LAST_DEVICE, name).apply()
        Log.i(TAG, "connected $name (${ports.size} port(s))")
        refresh()
    }

    fun disconnect(id: String) {
        close(id)
        prefs.edit().remove(LAST_DEVICE).apply()
        refresh()
    }

    private fun close(id: String) {
        open.remove(id)?.let { c ->
            // Leave the controller dark rather than showing a stale state
            send(c.inputs, leds.allOff())
            c.inputs.forEach { runCatching { it.close() } }
            c.outputs.forEach { runCatching { it.disconnect(receiver); it.close() } }
            runCatching { c.device.close() }
            leds.reset()
        }
    }

    // --- Bluetooth LE MIDI ------------------------------------------------------------

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            bluetoothFound["ble:${result.device.address}"] = result.device
            refresh()
        }
    }

    /** Runtime permissions needed before [scanBluetooth]. */
    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Looks for Bluetooth LE MIDI controllers for 10 seconds. */
    @SuppressLint("MissingPermission")
    fun scanBluetooth(): String? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return "This device has no Bluetooth"
        if (!adapter.isEnabled) return "Turn on Bluetooth first"
        val scanner = adapter.bluetoothLeScanner ?: return "Bluetooth LE is not available"
        return try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().build(), scanCallback)
            _scanning.update { true }
            main.postDelayed({
                runCatching { scanner.stopScan(scanCallback) }
                _scanning.update { false }
            }, 10_000)
            null
        } catch (e: SecurityException) {
            "Bluetooth permission missing"
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address

    private companion object {
        const val TAG = "MidiController"
        const val LAST_DEVICE = "last_device"
        const val LED_VALUE = "led_value"
        const val LED_INTERVAL_MS = 50L
        /** Bluetooth LE MIDI service, from the BLE-MIDI specification. */
        val MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}
