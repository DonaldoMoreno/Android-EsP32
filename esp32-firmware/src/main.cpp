/**
 * @file main.cpp
 * @brief ThermoCirculator – ESP32-S3 Firmware
 *
 * Features:
 *  - USB CDC serial communication (native USB, no UART adapter)
 *  - PT100 temperature sensing via MAX31865 SPI module
 *  - PID controller for SSR-based heater control (zero-crossing / full cycles)
 *  - Protocol: SET, START, STOP, STATUS? / TEMP, POWER, STATE
 *  - Safety: max temperature limit + watchdog for Android comms loss
 *
 * Wiring (defaults, configurable via platformio.ini build_flags):
 *  MAX31865 CS   → GPIO 10
 *  MAX31865 MOSI → GPIO 11
 *  MAX31865 MISO → GPIO 13
 *  MAX31865 CLK  → GPIO 12
 *  SSR Gate      → GPIO 4
 *  USB D-        → GPIO 19 (native, no config needed for ESP32-S3)
 *  USB D+        → GPIO 20 (native)
 */

#include <Arduino.h>
#include <USB.h>
#include <USBCDC.h>
#include <Adafruit_MAX31865.h>
#include <SPI.h>
#include "PIDController.h"

// ----------------------------------------------------------------------------------
// Build-flag defaults (overridden by platformio.ini)
// ----------------------------------------------------------------------------------
#ifndef MAX31865_CS_PIN
#  define MAX31865_CS_PIN   10
#endif
#ifndef MAX31865_MOSI_PIN
#  define MAX31865_MOSI_PIN 11
#endif
#ifndef MAX31865_MISO_PIN
#  define MAX31865_MISO_PIN 13
#endif
#ifndef MAX31865_CLK_PIN
#  define MAX31865_CLK_PIN  12
#endif
#ifndef SSR_PIN
#  define SSR_PIN           4
#endif
#ifndef MAX_TEMP_LIMIT
#  define MAX_TEMP_LIMIT    150   // °C
#endif
#ifndef COMMS_TIMEOUT_SEC
#  define COMMS_TIMEOUT_SEC 10
#endif

// ----------------------------------------------------------------------------------
// Constants
// ----------------------------------------------------------------------------------
static constexpr float  PT100_REF_RESISTANCE  = 430.0f;   // Ω – match your MAX31865 module
static constexpr float  PT100_NOMINAL          = 100.0f;   // Ω @ 0 °C
static constexpr float  STABLE_TOLERANCE_DEG   = 1.5f;    // °C band for STABLE state
static constexpr uint32_t READ_INTERVAL_MS     = 500;     // sensor polling interval
static constexpr uint32_t REPORT_INTERVAL_MS   = 500;     // USB report interval
static constexpr uint32_t LINE_FREQ_HZ         = 50;      // mains frequency
static constexpr uint32_t HALF_CYCLE_US        = 1000000UL / (LINE_FREQ_HZ * 2); // µs per half cycle

// PID default gains (tune for your thermal mass)
static constexpr float PID_KP = 2.0f;
static constexpr float PID_KI = 0.3f;
static constexpr float PID_KD = 0.5f;

// ----------------------------------------------------------------------------------
// System states
// ----------------------------------------------------------------------------------
enum class SystemState : uint8_t {
    IDLE    = 0,
    HEATING = 1,
    STABLE  = 2,
    ERROR   = 3
};

static const char* stateToStr(SystemState s) {
    switch (s) {
        case SystemState::IDLE:    return "IDLE";
        case SystemState::HEATING: return "HEATING";
        case SystemState::STABLE:  return "STABLE";
        case SystemState::ERROR:   return "ERROR";
        default:                   return "IDLE";
    }
}

// ----------------------------------------------------------------------------------
// Global objects
// ----------------------------------------------------------------------------------
USBCDC  SerialUSB;
Adafruit_MAX31865 max31865(MAX31865_CS_PIN, MAX31865_MOSI_PIN, MAX31865_MISO_PIN, MAX31865_CLK_PIN);
PIDController pid(PID_KP, PID_KI, PID_KD);

// ----------------------------------------------------------------------------------
// State variables
// ----------------------------------------------------------------------------------
SystemState systemState     = SystemState::IDLE;
float       targetTemp      = 50.0f;      // °C setpoint
float       currentTemp     = 0.0f;       // °C measured
float       powerPercent    = 0.0f;       // 0–100 PID output
bool        heatingEnabled  = false;

// Timing
uint32_t    lastReadMs      = 0;
uint32_t    lastReportMs    = 0;
uint32_t    lastCommsMs     = 0;          // last time Android sent a command
uint32_t    lastPIDMs       = 0;

// USB receive buffer
static char rxBuffer[128];
static int  rxIdx = 0;

// ----------------------------------------------------------------------------------
// Forward declarations
// ----------------------------------------------------------------------------------
void handleCommand(const char* cmd);
void sendReport();
void updateSSR();
void enterError(const char* reason);
float readTemperature(bool& sensorOk);

// ----------------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------------
void setup() {
    // Native USB CDC
    USB.begin();
    SerialUSB.begin(0);   // baud rate ignored for CDC; 0 = use hardware default

    // SSR output – active HIGH
    pinMode(SSR_PIN, OUTPUT);
    digitalWrite(SSR_PIN, LOW);

    // MAX31865 – 2-wire PT100 (change MAX31865_2WIRE → 3WIRE / 4WIRE as needed)
    max31865.begin(MAX31865_2WIRE);

    pid.setOutputLimits(0.0f, 100.0f);

    lastCommsMs = millis(); // start watchdog from boot
}

// ----------------------------------------------------------------------------------
// Main loop
// ----------------------------------------------------------------------------------
void loop() {
    uint32_t now = millis();

    // ---- 1. Receive USB commands ----
    while (SerialUSB.available()) {
        char c = (char)SerialUSB.read();
        if (c == '\n' || c == '\r') {
            if (rxIdx > 0) {
                rxBuffer[rxIdx] = '\0';
                handleCommand(rxBuffer);
                rxIdx = 0;
            }
        } else if (rxIdx < (int)(sizeof(rxBuffer) - 2)) {
            rxBuffer[rxIdx++] = c;
        }
        lastCommsMs = now;
    }

    // ---- 2. Communications watchdog ----
    if (heatingEnabled) {
        uint32_t elapsed = now - lastCommsMs;
        if (elapsed > (uint32_t)(COMMS_TIMEOUT_SEC * 1000UL)) {
            heatingEnabled = false;
            digitalWrite(SSR_PIN, LOW);
            systemState = SystemState::IDLE;
            SerialUSB.println("STATE IDLE");
            SerialUSB.println("POWER 0");
        }
    }

    // ---- 3. Sensor reading ----
    if (now - lastReadMs >= READ_INTERVAL_MS) {
        lastReadMs = now;
        bool sensorOk = true;
        currentTemp = readTemperature(sensorOk);

        if (!sensorOk) {
            enterError("Sensor fault");
            return;
        }

        // Over-temperature safety
        if (currentTemp >= (float)MAX_TEMP_LIMIT) {
            heatingEnabled = false;
            enterError("Over-temperature");
            return;
        }
    }

    // ---- 4. PID computation & SSR control ----
    if (heatingEnabled && now - lastPIDMs >= READ_INTERVAL_MS) {
        float dt = (now - lastPIDMs) / 1000.0f;
        lastPIDMs = now;

        powerPercent = pid.compute(targetTemp, currentTemp, dt);

        // Update system state based on error
        float error = targetTemp - currentTemp;
        if (fabsf(error) <= STABLE_TOLERANCE_DEG) {
            systemState = SystemState::STABLE;
        } else {
            systemState = SystemState::HEATING;
        }

        updateSSR();
    }

    // ---- 5. Periodic USB report ----
    if (now - lastReportMs >= REPORT_INTERVAL_MS) {
        lastReportMs = now;
        sendReport();
    }
}

// ----------------------------------------------------------------------------------
// Temperature reading
// ----------------------------------------------------------------------------------
float readTemperature(bool& sensorOk) {
    uint16_t rtd = max31865.readRTD();
    uint8_t  fault = max31865.readFault();

    if (fault) {
        max31865.clearFault();
        sensorOk = false;
        return 0.0f;
    }

    sensorOk = true;
    // Convert RTD ratio to temperature using Adafruit helper
    return max31865.temperature(PT100_NOMINAL, PT100_REF_RESISTANCE);
}

// ----------------------------------------------------------------------------------
// SSR control – whole AC cycle method
// Converts powerPercent (0–100) to ON cycles in a 10-cycle window.
// ----------------------------------------------------------------------------------
void updateSSR() {
    // Simple burst-fire: compute number of ON half-cycles within a 20-half-cycle window
    static uint8_t cycleCounter = 0;
    static uint8_t onCycles     = 0;

    cycleCounter++;
    if (cycleCounter >= 20) {
        cycleCounter = 0;
        onCycles = (uint8_t)((powerPercent / 100.0f) * 20.0f + 0.5f);
    }

    if (cycleCounter < onCycles) {
        digitalWrite(SSR_PIN, HIGH);
    } else {
        digitalWrite(SSR_PIN, LOW);
    }
}

// ----------------------------------------------------------------------------------
// USB report
// ----------------------------------------------------------------------------------
void sendReport() {
    // Format: "TEMP xx.x\n", "POWER xx\n", "STATE xxxx\n"
    char buf[32];
    snprintf(buf, sizeof(buf), "TEMP %.1f", currentTemp);
    SerialUSB.println(buf);

    snprintf(buf, sizeof(buf), "POWER %d", (int)powerPercent);
    SerialUSB.println(buf);

    snprintf(buf, sizeof(buf), "STATE %s", stateToStr(systemState));
    SerialUSB.println(buf);
}

// ----------------------------------------------------------------------------------
// Command handler
// ----------------------------------------------------------------------------------
void handleCommand(const char* cmd) {
    // Trim leading spaces
    while (*cmd == ' ') cmd++;

    if (strncmp(cmd, "SET ", 4) == 0) {
        float val = atof(cmd + 4);
        if (val > 0.0f && val < (float)MAX_TEMP_LIMIT) {
            targetTemp = val;
            pid.reset();
            SerialUSB.print("ACK SET ");
            SerialUSB.println(val, 1);
        }

    } else if (strcmp(cmd, "START") == 0) {
        if (systemState != SystemState::ERROR) {
            heatingEnabled = true;
            systemState    = SystemState::HEATING;
            lastPIDMs      = millis();
            pid.reset();
            SerialUSB.println("STATE HEATING");
        }

    } else if (strcmp(cmd, "STOP") == 0) {
        heatingEnabled = false;
        powerPercent   = 0.0f;
        systemState    = SystemState::IDLE;
        digitalWrite(SSR_PIN, LOW);
        SerialUSB.println("STATE IDLE");
        SerialUSB.println("POWER 0");

    } else if (strcmp(cmd, "STATUS?") == 0) {
        sendReport();

    } else {
        SerialUSB.print("ERR UNKNOWN ");
        SerialUSB.println(cmd);
    }
}

// ----------------------------------------------------------------------------------
// Error handling
// ----------------------------------------------------------------------------------
void enterError(const char* reason) {
    heatingEnabled = false;
    powerPercent   = 0.0f;
    systemState    = SystemState::ERROR;
    digitalWrite(SSR_PIN, LOW);

    SerialUSB.print("STATE ERROR ");
    SerialUSB.println(reason);
    SerialUSB.println("POWER 0");
}
