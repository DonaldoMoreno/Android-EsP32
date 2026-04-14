package com.thermocirculator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.thermocirculator.R
import com.thermocirculator.model.UsbConnectionState
import com.thermocirculator.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the USB serial connection to the ESP32-S3.
 *
 * This service:
 * - Detects ESP32-S3 USB devices
 * - Opens and maintains the serial port (CDC, 115200 baud)
 * - Sends commands: SET, START, STOP, STATUS?
 * - Receives and parses responses: TEMP, POWER, STATE
 * - Handles reconnection on disconnect
 * - Posts updates via [ServiceCallback]
 */
class UsbSerialService : Service() {

    companion object {
        private const val TAG = "UsbSerialService"
        private const val BAUD_RATE = 115200
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 2000
        private const val RECONNECT_DELAY_MS = 3000L
        private const val STATUS_POLL_INTERVAL_MS = 500L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "thermo_channel"

        const val ACTION_USB_PERMISSION = "com.thermocirculator.USB_PERMISSION"

        /** Intent extra key for incoming serial data (broadcast to ViewModel). */
        const val EXTRA_DATA = "extra_data"
        const val ACTION_DATA_RECEIVED = "com.thermocirculator.DATA_RECEIVED"
        const val ACTION_CONNECTION_CHANGED = "com.thermocirculator.CONNECTION_CHANGED"
        const val EXTRA_CONNECTION_STATE = "extra_connection_state"
    }

    inner class LocalBinder : Binder() {
        fun getService(): UsbSerialService = this@UsbSerialService
    }

    private val binder = LocalBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var readBuffer = StringBuilder()

    /** Callback interface for communicating with ViewModel. */
    var callback: ServiceCallback? = null

    interface ServiceCallback {
        fun onDataReceived(line: String)
        fun onConnectionStateChanged(state: UsbConnectionState)
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Esperando conexión USB…"))
        registerUsbReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Try connecting immediately if a device is already attached
        tryConnectToDevice()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unregisterUsbReceiver()
        closePort()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------------------
    // USB Receiver
    // ----------------------------------------------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    tryConnectToDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    closePort()
                    notifyConnectionState(UsbConnectionState.DISCONNECTED)
                    scheduleReconnect()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        device?.let { openDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        notifyConnectionState(UsbConnectionState.ERROR)
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun unregisterUsbReceiver() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    // ----------------------------------------------------------------------------------
    // Connection Management
    // ----------------------------------------------------------------------------------

    fun tryConnectToDevice() {
        val manager = usbManager ?: return
        val drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices found")
            return
        }
        val driver = drivers.first()
        val device = driver.device
        if (!manager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for ${device.deviceName}")
            notifyConnectionState(UsbConnectionState.CONNECTING)
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.requestPermission(device, permissionIntent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.firstOrNull { it.device == device } ?: return

        notifyConnectionState(UsbConnectionState.CONNECTING)
        serviceScope.launch {
            try {
                val connection = manager.openDevice(device)
                    ?: throw IllegalStateException("Could not open USB device")
                val port = driver.ports.first()
                port.open(connection)
                port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                serialPort = port
                Log.i(TAG, "Serial port opened: ${device.deviceName} @ $BAUD_RATE baud")
                notifyConnectionState(UsbConnectionState.CONNECTED)
                startReading()
                startStatusPolling()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open serial port", e)
                notifyConnectionState(UsbConnectionState.ERROR)
                scheduleReconnect()
            }
        }
    }

    private fun closePort() {
        readJob?.cancel()
        readJob = null
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            tryConnectToDevice()
        }
    }

    // ----------------------------------------------------------------------------------
    // Reading
    // ----------------------------------------------------------------------------------

    private fun startReading() {
        readJob?.cancel()
        readJob = serviceScope.launch {
            val buf = ByteArray(256)
            while (isActive) {
                try {
                    val port = serialPort ?: break
                    val len = port.read(buf, READ_TIMEOUT_MS)
                    if (len > 0) {
                        val chunk = String(buf, 0, len, Charsets.UTF_8)
                        readBuffer.append(chunk)
                        processBuffer()
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Read error: ${e.message}")
                        closePort()
                        notifyConnectionState(UsbConnectionState.DISCONNECTED)
                        scheduleReconnect()
                        break
                    }
                }
            }
        }
    }

    private fun processBuffer() {
        var newlineIdx = readBuffer.indexOf('\n')
        while (newlineIdx != -1) {
            val line = readBuffer.substring(0, newlineIdx).trim()
            readBuffer.delete(0, newlineIdx + 1)
            if (line.isNotEmpty()) {
                Log.d(TAG, "RX: $line")
                callback?.onDataReceived(line)
            }
            newlineIdx = readBuffer.indexOf('\n')
        }
        // Prevent buffer overflow from malformed data
        if (readBuffer.length > 1024) {
            readBuffer.clear()
        }
    }

    // ----------------------------------------------------------------------------------
    // Status Polling
    // ----------------------------------------------------------------------------------

    private fun startStatusPolling() {
        serviceScope.launch {
            while (isActive && serialPort != null) {
                sendCommand("STATUS?")
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Public Commands
    // ----------------------------------------------------------------------------------

    /** Send target temperature setpoint to ESP32-S3. */
    fun sendSetTemperature(tempCelsius: Float) {
        sendCommand("SET %.1f".format(tempCelsius))
    }

    /** Send START command to begin PID control. */
    fun sendStart() {
        sendCommand("START")
    }

    /** Send STOP command to halt heating. */
    fun sendStop() {
        sendCommand("STOP")
    }

    /** Request current status from ESP32-S3. */
    fun sendStatusRequest() {
        sendCommand("STATUS?")
    }

    private fun sendCommand(command: String) {
        serviceScope.launch {
            try {
                val port = serialPort ?: run {
                    Log.w(TAG, "Cannot send '$command': port not open")
                    return@launch
                }
                val data = "$command\n".toByteArray(Charsets.UTF_8)
                port.write(data, WRITE_TIMEOUT_MS)
                Log.d(TAG, "TX: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Write error sending '$command'", e)
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    private fun notifyConnectionState(state: UsbConnectionState) {
        callback?.onConnectionStateChanged(state)
        updateNotification(state)
    }

    private fun updateNotification(state: UsbConnectionState) {
        val text = when (state) {
            UsbConnectionState.CONNECTED    -> "ESP32-S3 conectado"
            UsbConnectionState.CONNECTING   -> "Conectando…"
            UsbConnectionState.DISCONNECTED -> "Desconectado"
            UsbConnectionState.ERROR        -> "Error de conexión"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThermoCirculator")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_thermometer)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ThermoCirculator Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones del servicio USB serial"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
