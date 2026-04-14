package com.thermocirculator

import com.thermocirculator.model.SystemState
import com.thermocirculator.model.ThermoState
import com.thermocirculator.model.UsbConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for model classes and protocol parsing logic.
 *
 * These tests cover:
 * - [SystemState.fromString] parsing
 * - [ThermoState] default values and copy behaviour
 */
class ThermoModelTest {

    // ----------------------------------------------------------------------------------
    // SystemState.fromString
    // ----------------------------------------------------------------------------------

    @Test
    fun `fromString returns IDLE for IDLE`() {
        assertEquals(SystemState.IDLE, SystemState.fromString("IDLE"))
    }

    @Test
    fun `fromString returns HEATING for HEATING`() {
        assertEquals(SystemState.HEATING, SystemState.fromString("HEATING"))
    }

    @Test
    fun `fromString returns STABLE for STABLE`() {
        assertEquals(SystemState.STABLE, SystemState.fromString("STABLE"))
    }

    @Test
    fun `fromString returns ERROR for ERROR`() {
        assertEquals(SystemState.ERROR, SystemState.fromString("ERROR"))
    }

    @Test
    fun `fromString is case-insensitive`() {
        assertEquals(SystemState.HEATING, SystemState.fromString("heating"))
        assertEquals(SystemState.STABLE, SystemState.fromString("Stable"))
    }

    @Test
    fun `fromString returns IDLE for unknown string`() {
        assertEquals(SystemState.IDLE, SystemState.fromString("UNKNOWN"))
        assertEquals(SystemState.IDLE, SystemState.fromString(""))
    }

    // ----------------------------------------------------------------------------------
    // ThermoState defaults
    // ----------------------------------------------------------------------------------

    @Test
    fun `ThermoState has sane defaults`() {
        val state = ThermoState()
        assertEquals(0f, state.currentTemperature)
        assertEquals(0f, state.targetTemperature)
        assertEquals(0, state.powerPercent)
        assertEquals(SystemState.IDLE, state.systemState)
    }

    @Test
    fun `ThermoState copy updates only specified field`() {
        val base = ThermoState(currentTemperature = 25f, powerPercent = 50)
        val updated = base.copy(currentTemperature = 75f)
        assertEquals(75f, updated.currentTemperature)
        assertEquals(50, updated.powerPercent)
    }

    // ----------------------------------------------------------------------------------
    // Protocol message parsing simulation
    // ----------------------------------------------------------------------------------

    @Test
    fun `TEMP message parses correctly`() {
        val line = "TEMP 72.5"
        assertTrue(line.startsWith("TEMP "))
        val value = line.removePrefix("TEMP ").toFloatOrNull()
        assertEquals(72.5f, value)
    }

    @Test
    fun `POWER message parses correctly`() {
        val line = "POWER 45"
        assertTrue(line.startsWith("POWER "))
        val value = line.removePrefix("POWER ").toIntOrNull()
        assertEquals(45, value)
    }

    @Test
    fun `STATE message parses correctly`() {
        val line = "STATE HEATING"
        assertTrue(line.startsWith("STATE "))
        val state = SystemState.fromString(line.removePrefix("STATE "))
        assertEquals(SystemState.HEATING, state)
    }

    @Test
    fun `TEMP message with malformed value returns null`() {
        val line = "TEMP abc"
        val value = line.removePrefix("TEMP ").toFloatOrNull()
        assertNull(value)
    }

    @Test
    fun `POWER message with malformed value returns null`() {
        val line = "POWER xyz"
        val value = line.removePrefix("POWER ").toIntOrNull()
        assertNull(value)
    }

    // ----------------------------------------------------------------------------------
    // UsbConnectionState
    // ----------------------------------------------------------------------------------

    @Test
    fun `UsbConnectionState has expected values`() {
        val states = UsbConnectionState.values()
        assertTrue(states.contains(UsbConnectionState.DISCONNECTED))
        assertTrue(states.contains(UsbConnectionState.CONNECTING))
        assertTrue(states.contains(UsbConnectionState.CONNECTED))
        assertTrue(states.contains(UsbConnectionState.ERROR))
    }
}
