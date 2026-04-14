package com.thermocirculator.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.thermocirculator.databinding.ActivityMainBinding
import com.thermocirculator.model.SystemState
import com.thermocirculator.model.TemperaturePoint
import com.thermocirculator.model.UsbConnectionState
import com.thermocirculator.service.UsbSerialService
import com.thermocirculator.viewmodel.MainViewModel

/**
 * Main screen of ThermoCirculator.
 *
 * Layout sections:
 * 1. Connection status indicator
 * 2. Real-time temperature chart (MPAndroidChart)
 * 3. Current temperature & power readings
 * 4. Target temperature input
 * 5. Start/Stop button
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChart()
        setupListeners()
        observeViewModel()

        // Bind and start the USB service
        viewModel.bindService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Triggered when USB device is attached while app is running
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            viewModel.bindService()
        }
    }

    override fun onDestroy() {
        viewModel.unbindService()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------------------
    // UI Setup
    // ----------------------------------------------------------------------------------

    private fun setupChart() {
        with(binding.temperatureChart) {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setBackgroundColor(Color.TRANSPARENT)
            setGridBackgroundColor(Color.TRANSPARENT)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                gridColor = Color.DKGRAY
                setDrawAxisLine(true)
                setDrawGridLines(true)
            }
            axisLeft.apply {
                textColor = Color.WHITE
                gridColor = Color.DKGRAY
                axisMinimum = 0f
                axisMaximum = 200f
            }
            axisRight.isEnabled = false
            legend.textColor = Color.WHITE

            // Empty initial dataset
            data = LineData(createTempDataSet(emptyList()))
        }
    }

    private fun createTempDataSet(points: List<TemperaturePoint>): LineDataSet {
        val entries = points.mapIndexed { index, point ->
            Entry(index.toFloat(), point.temperature)
        }
        return LineDataSet(entries, "Temperatura (°C)").apply {
            color = Color.parseColor("#FF6D3B")
            setCircleColor(Color.parseColor("#FF6D3B"))
            lineWidth = 2f
            circleRadius = 2f
            setDrawCircleHole(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
        }
    }

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            val inputText = binding.etTargetTemp.text.toString()
            val temp = inputText.toFloatOrNull()
            if (temp == null || temp < 0f || temp > 200f) {
                binding.etTargetTemp.error = "Ingrese una temperatura válida (0–200 °C)"
                return@setOnClickListener
            }
            viewModel.setTargetTemperature(temp)
            viewModel.toggleStartStop()
        }
    }

    // ----------------------------------------------------------------------------------
    // ViewModel Observation
    // ----------------------------------------------------------------------------------

    private fun observeViewModel() {
        viewModel.thermoState.observe(this) { state ->
            binding.tvCurrentTemp.text = "%.1f °C".format(state.currentTemperature)
            binding.tvPower.text = "${state.powerPercent}%"
            binding.tvSystemState.text = state.systemState.name
            binding.tvSystemState.setTextColor(stateColor(state.systemState))
        }

        viewModel.isRunning.observe(this) { running ->
            binding.btnStartStop.text = if (running) "⏹ STOP" else "▶ START"
            binding.btnStartStop.setBackgroundColor(
                if (running) Color.parseColor("#D32F2F") else Color.parseColor("#388E3C")
            )
        }

        viewModel.connectionState.observe(this) { state ->
            val (text, color) = when (state) {
                UsbConnectionState.CONNECTED    -> Pair("● Conectado", Color.parseColor("#4CAF50"))
                UsbConnectionState.CONNECTING   -> Pair("○ Conectando…", Color.parseColor("#FF9800"))
                UsbConnectionState.DISCONNECTED -> Pair("○ Desconectado", Color.parseColor("#9E9E9E"))
                UsbConnectionState.ERROR        -> Pair("✗ Error USB", Color.parseColor("#F44336"))
            }
            binding.tvConnectionStatus.text = text
            binding.tvConnectionStatus.setTextColor(color)
        }

        viewModel.temperatureHistory.observe(this) { history ->
            updateChart(history)
        }

        viewModel.errorMessage.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    private fun updateChart(history: List<TemperaturePoint>) {
        if (history.isEmpty()) return
        val chart = binding.temperatureChart
        val dataset = createTempDataSet(history)
        chart.data = LineData(dataset)
        chart.notifyDataSetChanged()
        chart.invalidate()
        // Auto-scroll to latest point
        chart.moveViewToX(history.size.toFloat())
    }

    private fun stateColor(state: SystemState): Int = when (state) {
        SystemState.HEATING -> Color.parseColor("#FF9800")
        SystemState.STABLE  -> Color.parseColor("#4CAF50")
        SystemState.ERROR   -> Color.parseColor("#F44336")
        SystemState.IDLE    -> Color.parseColor("#9E9E9E")
    }
}
