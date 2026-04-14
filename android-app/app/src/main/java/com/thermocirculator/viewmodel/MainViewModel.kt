package com.thermocirculator.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.thermocirculator.model.NfcEvent
import com.thermocirculator.model.SystemState
import com.thermocirculator.model.TemperaturePoint
import com.thermocirculator.model.ThermoState
import com.thermocirculator.model.UsbConnectionState
import com.thermocirculator.service.UsbSerialService

/**
 * ViewModel for [com.thermocirculator.ui.MainActivity].
 *
 * Responsibilities:
 * - Binds to [UsbSerialService] and acts as its [UsbSerialService.ServiceCallback]
 * - Parses incoming serial messages (TEMP, POWER, STATE)
 * - Exposes [LiveData] streams consumed by the UI
 * - Sends commands to the ESP32-S3 via the service
 */
class MainViewModel(application: Application) : AndroidViewModel(application),
    UsbSerialService.ServiceCallback {

    companion object {
        private const val TAG = "MainViewModel"
        /** Maximum number of temperature history points shown on chart. */
        private const val MAX_HISTORY_POINTS = 300
    }

    // ----------------------------------------------------------------------------------
    // LiveData exposed to UI
    // ----------------------------------------------------------------------------------

    private val _thermoState = MutableLiveData(ThermoState())
    val thermoState: LiveData<ThermoState> = _thermoState

    private val _connectionState = MutableLiveData(UsbConnectionState.DISCONNECTED)
    val connectionState: LiveData<UsbConnectionState> = _connectionState

    private val _temperatureHistory = MutableLiveData<List<TemperaturePoint>>(emptyList())
    val temperatureHistory: LiveData<List<TemperaturePoint>> = _temperatureHistory

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _lastNfcEvent = MutableLiveData<NfcEvent?>(null)
    val lastNfcEvent: LiveData<NfcEvent?> = _lastNfcEvent

    // ----------------------------------------------------------------------------------
    // Internal state
    // ----------------------------------------------------------------------------------

    private val historyBuffer = ArrayDeque<TemperaturePoint>(MAX_HISTORY_POINTS + 1)
    private var targetTemperature: Float = 50f

    // ----------------------------------------------------------------------------------
    // Service binding
    // ----------------------------------------------------------------------------------

    private var usbService: UsbSerialService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as UsbSerialService.LocalBinder
            usbService = binder.getService()
            usbService?.callback = this@MainViewModel
            serviceBound = true
            Log.d(TAG, "UsbSerialService bound")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            usbService = null
            serviceBound = false
            Log.d(TAG, "UsbSerialService unbound")
        }
    }

    fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, UsbSerialService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (serviceBound) {
            usbService?.callback = null
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ----------------------------------------------------------------------------------
    // ServiceCallback implementation
    // ----------------------------------------------------------------------------------

    /**
     * Called on the IO thread by [UsbSerialService] for every complete line received.
     * All LiveData updates use [postValue] to marshal to the main thread.
     */
    override fun onDataReceived(line: String) {
        Log.d(TAG, "onDataReceived: $line")
        parseMessage(line)
    }

    override fun onConnectionStateChanged(state: UsbConnectionState) {
        _connectionState.postValue(state)
        if (state == UsbConnectionState.DISCONNECTED || state == UsbConnectionState.ERROR) {
            // Reflect hardware disconnect in UI
            _isRunning.postValue(false)
        }
    }

    // ----------------------------------------------------------------------------------
    // Message parsing
    // ----------------------------------------------------------------------------------

    /**
     * Parses a single message line from the ESP32-S3.
     *
     * Supported formats:
     * - `TEMP xx.x`  → temperature reading
     * - `POWER xx`   → heater power %
     * - `STATE xxxx` → system state
     */
    private fun parseMessage(line: String) {
        val trimmed = line.trim()
        val current = _thermoState.value ?: ThermoState()

        when {
            trimmed.startsWith("TEMP ") -> {
                val value = trimmed.removePrefix("TEMP ").toFloatOrNull()
                if (value != null) {
                    val updated = current.copy(currentTemperature = value)
                    _thermoState.postValue(updated)
                    appendHistory(value)
                }
            }
            trimmed.startsWith("POWER ") -> {
                val value = trimmed.removePrefix("POWER ").toIntOrNull()
                if (value != null) {
                    _thermoState.postValue(current.copy(powerPercent = value))
                }
            }
            trimmed.startsWith("STATE ") -> {
                val stateStr = trimmed.removePrefix("STATE ")
                val state = SystemState.fromString(stateStr)
                _thermoState.postValue(current.copy(systemState = state))
                _isRunning.postValue(state == SystemState.HEATING || state == SystemState.STABLE)
                if (state == SystemState.ERROR) {
                    _errorMessage.postValue("ESP32-S3 reporta ERROR. Verifique sensor y conexiones.")
                }
            }
            trimmed.startsWith("NFC UID ") -> {
                val uid = trimmed.removePrefix("NFC UID ").trim()
                if (uid.isNotEmpty()) {
                    _lastNfcEvent.postValue(NfcEvent(uid, System.currentTimeMillis()))
                }
            }
            else -> Log.w(TAG, "Unknown message: $trimmed")
        }
    }

    private fun appendHistory(temperature: Float) {
        val point = TemperaturePoint(System.currentTimeMillis(), temperature)
        if (historyBuffer.size >= MAX_HISTORY_POINTS) {
            historyBuffer.removeFirst()
        }
        historyBuffer.addLast(point)
        _temperatureHistory.postValue(historyBuffer.toList())
    }

    // ----------------------------------------------------------------------------------
    // User actions
    // ----------------------------------------------------------------------------------

    /** Set the target temperature without sending it yet. */
    fun setTargetTemperature(temp: Float) {
        targetTemperature = temp
        val current = _thermoState.value ?: ThermoState()
        _thermoState.value = current.copy(targetTemperature = temp)
    }

    /**
     * Toggle the heating cycle:
     * - If running: sends STOP
     * - If stopped: sends SET + START
     */
    fun toggleStartStop() {
        val service = usbService ?: run {
            _errorMessage.value = "No hay conexión USB. Conecte el ESP32-S3."
            return
        }
        if (_isRunning.value == true) {
            service.sendStop()
            _isRunning.value = false
        } else {
            service.sendSetTemperature(targetTemperature)
            service.sendStart()
            _isRunning.value = true
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearNfcEvent() {
        _lastNfcEvent.value = null
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}
