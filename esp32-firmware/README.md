# ThermoCirculator – Firmware ESP32-S3

Firmware para controlar el sistema ThermoCirculator desde un ESP32-S3 usando USB CDC nativo.

---

## Hardware Requerido

| Componente       | Especificación                             |
|------------------|--------------------------------------------|
| ESP32-S3 DevKit  | Cualquier variante con USB nativo (D+ D-)  |
| MAX31865         | Módulo RTD SPI para PT100                  |
| PT100            | Sensor de temperatura 100Ω de 2, 3 o 4 hilos |
| SSR              | Solid State Relay (ej. SSR-25DA para 220V AC) |

---

## Conexiones Eléctricas

### MAX31865 → ESP32-S3

```
MAX31865          ESP32-S3
─────────         ─────────
VCC     ──────►  3.3V
GND     ──────►  GND
CS      ──────►  GPIO 10  (configurable: MAX31865_CS_PIN)
MOSI/DIN──────►  GPIO 11  (configurable: MAX31865_MOSI_PIN)
MISO/DOUT──────► GPIO 13  (configurable: MAX31865_MISO_PIN)
CLK/SCLK──────►  GPIO 12  (configurable: MAX31865_CLK_PIN)
```

### SSR → ESP32-S3

```
SSR               ESP32-S3
─────             ─────────
IN+     ──────►  GPIO 4  (configurable: SSR_PIN) via 330Ω resistor
IN-     ──────►  GND
```

> ⚠️ **Importante**: Los SSR de 3-32VDC de control funcionan directamente con 3.3V del ESP32-S3.
> Añadir una resistencia de 330Ω en serie para limitar corriente de gate.

### PT100 → MAX31865

**Configuración de 2 hilos** (predeterminado en firmware):

```
PT100             MAX31865
─────             ─────────
Hilo 1  ──────►  F+
Hilo 2  ──────►  RTD- y FORCE-  (puentear)
```

**Configuración de 4 hilos**: Cambiar `MAX31865_2WIRE` por `MAX31865_4WIRE` en `src/main.cpp`.

### Diagrama de Conexión Completo

```
                    ESP32-S3
                 ┌─────────────┐
           GND ──┤ GND         │
          3.3V ──┤ 3V3         │
       GPIO 10 ──┤ CS          ├──► MAX31865 CS
       GPIO 11 ──┤ MOSI        ├──► MAX31865 DIN
       GPIO 12 ──┤ CLK         ├──► MAX31865 CLK
       GPIO 13 ──┤ MISO        ◄── MAX31865 DOUT
                 │             │
        GPIO 4 ──┤ SSR_PIN     ├──[330Ω]──► SSR IN+
           GND ──┤             ├──────────► SSR IN-
                 │             │
           D+  ──┤ USB D+      │
           D-  ──┤ USB D-      │
                 └─────────────┘

MAX31865:
  ┌─────────┐
  │  F+     ├──► PT100 Hilo A
  │  RTD-   ├──► PT100 Hilo B
  │  FORCE- ├──► PT100 Hilo B (2-wire: puentear con RTD-)
  └─────────┘

SSR → Carga CA (calefactor):
  SSR L1 → Fase AC 220V
  SSR L2 → Calefactor
  Calefactor → Neutro
```

---

## Configuración

Todos los parámetros configurables están en `platformio.ini` como `build_flags`:

| Flag                  | Por defecto | Descripción                                  |
|-----------------------|-------------|----------------------------------------------|
| `MAX31865_CS_PIN`     | 10          | GPIO para Chip Select del MAX31865            |
| `MAX31865_MOSI_PIN`   | 11          | GPIO para MOSI                                |
| `MAX31865_MISO_PIN`   | 13          | GPIO para MISO                                |
| `MAX31865_CLK_PIN`    | 12          | GPIO para SCK                                 |
| `SSR_PIN`             | 4           | GPIO para control del SSR                     |
| `MAX_TEMP_LIMIT`      | 150         | Temperatura máxima (°C) antes de apagado      |
| `COMMS_TIMEOUT_SEC`   | 10          | Segundos sin comms Android → apagado          |

### Ajuste de Ganancias PID

Las ganancias PID por defecto son adecuadas para un calefactor de 1-2kW con masa térmica moderada. Para afinar:

1. Editar en `src/main.cpp`:
   ```cpp
   static constexpr float PID_KP = 2.0f;
   static constexpr float PID_KI = 0.3f;
   static constexpr float PID_KD = 0.5f;
   ```
2. Compilar y flashear de nuevo.

**Método de ajuste Ziegler-Nichols simplificado**:
1. Poner `Ki = 0` y `Kd = 0`, aumentar `Kp` hasta que el sistema oscile.
2. Anotar el periodo de oscilación `Tu` y ganancia crítica `Ku`.
3. Usar: `Kp = 0.6*Ku`, `Ki = 1.2*Ku/Tu`, `Kd = 0.075*Ku*Tu`.

---

## Compilación y Flasheo

### Requisitos

- [PlatformIO Core CLI](https://docs.platformio.org/en/latest/core/installation/) o
- [VSCode + extensión PlatformIO](https://platformio.org/install/ide?install=vscode)

### Pasos

```bash
# 1. Ir a la carpeta del firmware
cd esp32-firmware/

# 2. Instalar dependencias y compilar
pio run

# 3. Conectar ESP32-S3 por USB al PC y flashear
pio run --target upload

# 4. Verificar con monitor serie (opcional)
pio device monitor --baud 115200
```

### Primer flasheo (Modo Bootloader)

Si el ESP32-S3 no entra automáticamente en modo de descarga:

1. Mantener presionado **BOOT**
2. Pulsar y soltar **RESET**
3. Soltar **BOOT**
4. Ejecutar `pio run --target upload`

---

## Protocolo USB CDC

El firmware comunica a **115200 baud** (ignorado en CDC nativo) con terminaciones `\n`.

### Comandos Recibidos (Android → ESP32-S3)

| Comando      | Descripción                          | Ejemplo       |
|-------------|--------------------------------------|---------------|
| `SET xx.x`  | Establece temperatura objetivo (°C)  | `SET 85.0`    |
| `START`     | Inicia PID y calentamiento           | `START`       |
| `STOP`      | Detiene calentamiento                | `STOP`        |
| `STATUS?`   | Solicita reporte inmediato           | `STATUS?`     |

### Mensajes Enviados (ESP32-S3 → Android)

| Mensaje       | Descripción                         | Ejemplo         |
|--------------|-------------------------------------|-----------------|
| `TEMP xx.x`  | Temperatura actual (°C)             | `TEMP 72.3`     |
| `POWER xx`   | Potencia actual (0–100%)            | `POWER 45`      |
| `STATE xxxx` | Estado del sistema                  | `STATE HEATING` |
| `ACK SET xx` | Confirmación de setpoint            | `ACK SET 85.0`  |
| `ERR ...`    | Mensaje de error                    | `ERR UNKNOWN X` |

---

## Estados del Sistema

| Estado    | Condición de Entrada                          | Salida SSR |
|-----------|-----------------------------------------------|------------|
| `IDLE`    | Inicial / STOP / timeout comms                | OFF        |
| `HEATING` | Después de START, temp < setpoint - tolerancia | PID output |
| `STABLE`  | temp dentro de ±1.5°C del setpoint            | PID output |
| `ERROR`   | Fallo sensor o sobretemperatura               | OFF forzado |

---

## Seguridad

El firmware implementa dos mecanismos de seguridad independientes:

1. **Límite de temperatura**: Si `currentTemp >= MAX_TEMP_LIMIT` (150°C por defecto), el SSR se apaga inmediatamente y el sistema entra en estado ERROR.

2. **Watchdog de comunicaciones**: Si no se recibe ningún comando de Android en `COMMS_TIMEOUT_SEC` segundos (10 por defecto), el calentamiento se detiene automáticamente. Esto protege contra desconexiones del dispositivo Android.

---

## Solución de Problemas

| Síntoma                      | Causa probable                          | Solución                              |
|------------------------------|-----------------------------------------|---------------------------------------|
| `STATE ERROR Sensor fault`   | MAX31865 no detectado o cableado incorrecto | Verificar conexiones SPI y CS pin |
| Temperatura siempre 0°C      | PT100 abierto o MAX31865 sin alimentación | Medir continuidad del PT100          |
| SSR no conmuta               | GPIO de SSR incorrecto                  | Verificar `SSR_PIN` en platformio.ini |
| Error de upload              | ESP32-S3 no en modo bootloader          | Mantener BOOT al resetear             |
| Temperatura inestable        | Ganancias PID mal ajustadas             | Ajustar Kp/Ki/Kd según masa térmica  |
