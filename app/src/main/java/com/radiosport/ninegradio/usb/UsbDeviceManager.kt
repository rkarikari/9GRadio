package com.radiosport.ninegradio.usb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages RTL-SDR USB device discovery, permission requests, and lifecycle events.
 *
 * Usage:
 *   1. Call [startListening] in Activity.onStart / Service.onCreate
 *   2. Observe [deviceEvents] for attach/detach/permission events
 *   3. Call [requestPermission] when a device is attached without permission
 *   4. Call [stopListening] when done
 */
class UsbDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbDeviceManager"
        const val ACTION_USB_PERMISSION = "com.radiosport.ninegradio.USB_PERMISSION"
    }

    sealed class DeviceEvent {
        data class Attached(val device: UsbDevice)           : DeviceEvent()
        data class Detached(val device: UsbDevice)           : DeviceEvent()
        data class PermissionGranted(val device: UsbDevice)  : DeviceEvent()
        data class PermissionDenied(val device: UsbDevice)   : DeviceEvent()
        object NonePresent                                    : DeviceEvent()
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 8)
    val deviceEvents: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                device ?: return
                if (!RtlSdrDevice.isSupported(device)) return

                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.i(TAG, "RTL-SDR attached: ${device.deviceName}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _events.emit(DeviceEvent.Attached(device))
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.i(TAG, "RTL-SDR detached: ${device.deviceName}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _events.emit(DeviceEvent.Detached(device))
                        }
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.i(TAG, "Permission ${if (granted) "GRANTED" else "DENIED"}: ${device.deviceName}")
                        CoroutineScope(Dispatchers.Main).launch {
                            _events.emit(
                                if (granted) DeviceEvent.PermissionGranted(device)
                                else         DeviceEvent.PermissionDenied(device)
                            )
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        Log.i(TAG, "USB device listener started")
    }

    fun stopListening() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { /* already gone */ }
        }
        receiver = null
    }

    // ─── Device management ────────────────────────────────────────────────────

    /**
     * Returns all currently connected RTL-SDR devices.
     */
    fun getConnectedDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { RtlSdrDevice.isSupported(it) }

    /**
     * Returns the first connected RTL-SDR device, or null.
     */
    fun getFirstDevice(): UsbDevice? = getConnectedDevices().firstOrNull()

    /**
     * Check if the app has permission for a specific device.
     */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Request USB permission for a device. Result arrives via [deviceEvents].
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            CoroutineScope(Dispatchers.Main).launch {
                _events.emit(DeviceEvent.PermissionGranted(device))
            }
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val permIntent = PendingIntent.getBroadcast(context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        usbManager.requestPermission(device, permIntent)
        Log.i(TAG, "USB permission requested for ${device.deviceName}")
    }

    /**
     * Open a device if permission is already granted.
     * @return UsbDeviceConnection or null
     */
    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return if (usbManager.hasPermission(device)) {
            usbManager.openDevice(device)
        } else {
            Log.w(TAG, "No permission to open ${device.deviceName}")
            null
        }
    }

    /**
     * Auto-handle: if a device is present with permission, emit PermissionGranted.
     * If present without permission, request it.
     * If none, emit NonePresent.
     */
    suspend fun autoConnect() {
        val device = getFirstDevice()
        when {
            device == null -> _events.emit(DeviceEvent.NonePresent)
            hasPermission(device) -> _events.emit(DeviceEvent.PermissionGranted(device))
            else -> requestPermission(device)
        }
    }

    // ─── Device info ─────────────────────────────────────────────────────────

    fun getDeviceInfo(device: UsbDevice): String = buildString {
        appendLine("Name:        ${device.deviceName}")
        appendLine("Product:     ${device.productName ?: "N/A"}")
        appendLine("Manufacturer:${device.manufacturerName ?: "N/A"}")
        appendLine("VID:         0x${device.vendorId.toString(16).uppercase()}")
        appendLine("PID:         0x${device.productId.toString(16).uppercase()}")
        appendLine("Type:        ${RtlSdrDevice.getDeviceName(device)}")
        appendLine("Interfaces:  ${device.interfaceCount}")
        appendLine("Permission:  ${if (usbManager.hasPermission(device)) "Granted" else "Not granted"}")
    }
}
