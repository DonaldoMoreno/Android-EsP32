# ThermoCirculator

Sistema de control de temperatura basado en ESP32-S3 con interfaz Android via USB OTG.

## Descripción del Proyecto

**ThermoCirculator** es un sistema de control de temperatura de lazo cerrado que utiliza:

- **ESP32-S3** como microcontrolador principal con comunicación USB CDC
- **Sensor PT100** con módulo MAX31865 para lectura de temperatura de precisión
- **Controlador PID** para regular la potencia de calentamiento
- **SSR (Solid State Relay)** para controlar la carga de calentamiento mediante ciclos completos
- **Aplicación Android** para monitoreo y control en tiempo real via USB OTG

---

## Arquitectura General

```
┌─────────────────────────────────────────────────┐
│              DISPOSITIVO ANDROID                │
│                                                 │
│  ┌──────────┐   ┌──────────────┐   ┌────────┐  │
│  │   UI     │◄─►│  ViewModel   │◄─►│ USB    │  │
│  │ (Gráfica,│   │  (MVVM)      │   │Service │  │
│  │  Botones)│   │              │   │        │  │
│  └──────────┘   └──────────────┘   └───┬────┘  │
└────────────────────────────────────────┼────────┘
                                         │ USB OTG
                                         │ (CDC Serial)
                              ┌──────────▼──────────┐
                              │      ESP32-S3        │
                              │                      │
                              │  ┌──────────────┐   │
                              │  │  PID Control  │   │
                              │  │  + Protocol   │   │
                              │  └──────┬────────┘   │
                              │         │             │
                              │  ┌──────▼────────┐   │
                              │  │  MAX31865 SPI  │   │
                              │  │  (PT100)       │   │
                              │  └───────────────┘   │
                              │  ┌───────────────┐   │
                              │  │  SSR GPIO      │   │
                              │  │  (Calefactor)  │   │
                              │  └───────────────┘   │
                              └──────────────────────┘
```

---

## Lista de Materiales

| Componente              | Descripción                              | Cantidad |
|-------------------------|------------------------------------------|----------|
| ESP32-S3 DevKit         | Microcontrolador principal (USB CDC)     | 1        |
| MAX31865                | Módulo amplificador PT100/RTD SPI        | 1        |
| PT100                   | Sensor de temperatura resistivo 100Ω     | 1        |
| SSR-25DA (o similar)    | Solid State Relay 25A para CA            | 1        |
| Cable USB-C OTG         | Para conexión Android ↔ ESP32-S3         | 1        |
| Resistencia 4.02kΩ      | Referencia MAX31865 (2 hilos PT100)      | 1        |
| Fuente 5V/2A            | Alimentación ESP32-S3                    | 1        |
| Disipador térmico       | Para SSR bajo carga                      | 1        |

---

## Esquema de Comunicación USB

Protocolo serial via USB CDC (Virtual COM Port):

### Android → ESP32-S3

| Comando      | Descripción                              | Ejemplo        |
|-------------|------------------------------------------|----------------|
| `SET xx.x`  | Establece temperatura objetivo (°C)      | `SET 85.5`     |
| `START`     | Inicia el control PID y calentamiento    | `START`        |
| `STOP`      | Detiene el calentamiento                 | `STOP`         |
| `STATUS?`   | Solicita estado completo                 | `STATUS?`      |

### ESP32-S3 → Android

| Mensaje       | Descripción                             | Ejemplo          |
|--------------|-----------------------------------------|------------------|
| `TEMP xx.x`  | Temperatura actual del sensor (°C)      | `TEMP 72.3`      |
| `POWER xx`   | Porcentaje de potencia aplicada (0-100) | `POWER 45`       |
| `STATE xxxx` | Estado actual del sistema               | `STATE HEATING`  |

### Estados del Sistema

| Estado    | Descripción                                          |
|-----------|------------------------------------------------------|
| `IDLE`    | Sistema en espera, calentamiento detenido            |
| `HEATING` | Calentando hacia temperatura objetivo                |
| `STABLE`  | Temperatura estable en objetivo (±tolerancia)        |
| `ERROR`   | Error detectado (sensor falla, sobretemperatura)     |

---

## Cómo Compilar la App Android

### Requisitos Previos

- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17
- Android SDK API 29+ (Android 10)
- Cable USB-C OTG

### Pasos

```bash
# 1. Clonar el repositorio
git clone https://github.com/DonaldoMoreno/Android-EsP32.git
cd Android-EsP32/android-app

# 2. Abrir en Android Studio
# File → Open → seleccionar carpeta android-app/

# 3. Sincronizar Gradle
# Android Studio sincronizará automáticamente las dependencias

# 4. Compilar
./gradlew assembleDebug

# 5. Instalar en dispositivo
./gradlew installDebug
```

Ver [android-app/README.md](android-app/README.md) para detalles completos.

---

## Cómo Flashear el ESP32-S3

### Requisitos Previos

- [PlatformIO](https://platformio.org/) (CLI o extensión VSCode)
- Driver USB para ESP32-S3

### Pasos

```bash
# 1. Ir a la carpeta del firmware
cd Android-EsP32/esp32-firmware

# 2. Compilar
pio run

# 3. Flashear (conectar ESP32-S3 por USB al PC)
pio run --target upload

# 4. Monitor serie (opcional, para debug)
pio device monitor --baud 115200
```

Ver [esp32-firmware/README.md](esp32-firmware/README.md) para detalles de conexiones.

---

## Diagrama de Bloques del Sistema

```
                    ┌─────────────────────────────────────┐
                    │           BLOQUE ANDROID             │
                    │                                      │
   Usuario ────────►│  MainActivity                        │
                    │      │                               │
                    │      ▼                               │
                    │  MainViewModel ◄──── LiveData ───┐   │
                    │      │                           │   │
                    │      ▼                           │   │
                    │  UsbSerialService ───────────────┘   │
                    │      │                               │
                    └──────┼───────────────────────────────┘
                           │ USB OTG (CDC 115200 baud)
                    ┌──────┼───────────────────────────────┐
                    │      ▼                               │
                    │  ESP32-S3 Firmware                   │
                    │  ┌────────────────────────────────┐  │
                    │  │  USB CDC Handler               │  │
                    │  │  ├── Parser de comandos        │  │
                    │  │  └── Serializer de respuestas  │  │
                    │  └────────────┬───────────────────┘  │
                    │               │                      │
                    │  ┌────────────▼───────────────────┐  │
                    │  │  Control PID                    │  │
                    │  │  ├── Kp, Ki, Kd configurables  │  │
                    │  │  ├── Anti-windup               │  │
                    │  │  └── Output 0-100%             │  │
                    │  └────────────┬───────────────────┘  │
                    │        ┌──────┴──────┐               │
                    │        ▼             ▼               │
                    │  ┌─────────┐  ┌──────────────┐      │
                    │  │MAX31865 │  │  SSR Control  │      │
                    │  │  SPI    │  │  GPIO         │      │
                    │  │  PT100  │  │  (50Hz ciclos)│      │
                    │  └─────────┘  └──────────────┘      │
                    └─────────────────────────────────────┘
```

---

## Licencia

MIT License - ver [LICENSE](LICENSE) para detalles.