package com.thermocirculator.model

/**
 * Represents a single data point in the temperature history.
 *
 * @param timestamp Unix timestamp in milliseconds.
 * @param temperature Temperature reading in degrees Celsius.
 */
data class TemperaturePoint(
    val timestamp: Long,
    val temperature: Float
)

/**
 * Represents the complete state received from the ESP32-S3.
 *
 * @param currentTemperature Current temperature reading (°C).
 * @param targetTemperature Desired setpoint temperature (°C).
 * @param powerPercent Heater power output (0-100%).
 * @param systemState Current operating state of the ESP32 firmware.
 */
data class ThermoState(
    val currentTemperature: Float = 0f,
    val targetTemperature: Float = 0f,
    val powerPercent: Int = 0,
    val systemState: SystemState = SystemState.IDLE
)

/**
 * Possible operating states of the ESP32-S3 firmware.
 */
enum class SystemState {
    IDLE,
    HEATING,
    STABLE,
    ERROR;

    companion object {
        fun fromString(value: String): SystemState = when (value.uppercase()) {
            "IDLE"    -> IDLE
            "HEATING" -> HEATING
            "STABLE"  -> STABLE
            "ERROR"   -> ERROR
            else      -> IDLE
        }
    }
}

/**
 * Represents an NFC card detection event received from the ESP32-S3.
 *
 * @param uid Hexadecimal UID string of the detected NFC card (e.g. "04A3B2C1").
 * @param timestamp Unix timestamp in milliseconds when the event was received.
 */
data class NfcEvent(
    val uid: String,
    val timestamp: Long
)

/**
 * Connection status of the USB serial device.
 */
enum class UsbConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
