# ThermoCirculator – Android App

Aplicación Android para controlar y monitorear el sistema ThermoCirculator via USB OTG.

---

## Características

- **USB OTG**: Comunicación directa con ESP32-S3 vía USB CDC sin necesidad de Bluetooth ni WiFi.
- **Gráfica en tiempo real**: Historial de temperatura con MPAndroidChart (hasta 300 puntos).
- **MVVM**: Arquitectura limpia con ViewModel + LiveData.
- **Reconexión automática**: Detecta y reconecta el dispositivo USB automáticamente.
- **Servicio en primer plano**: La conexión USB se mantiene aunque la app esté en segundo plano.

---

## Estructura del Proyecto

```
android-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/thermocirculator/
│   │   │   ├── model/
│   │   │   │   └── ThermoModels.kt         # Data classes y enums
│   │   │   ├── service/
│   │   │   │   └── UsbSerialService.kt     # Servicio USB foreground
│   │   │   ├── viewmodel/
│   │   │   │   └── MainViewModel.kt        # Lógica de negocio y datos
│   │   │   └── ui/
│   │   │       └── MainActivity.kt         # Pantalla principal
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml    # Layout de la pantalla
│   │   │   ├── values/strings.xml
│   │   │   ├── values/colors.xml
│   │   │   ├── values/themes.xml
│   │   │   └── xml/device_filter.xml       # Filtro de dispositivos USB
│   │   └── AndroidManifest.xml
│   └── src/test/
│       └── java/com/thermocirculator/
│           └── ThermoModelTest.kt          # Pruebas unitarias
├── build.gradle
├── settings.gradle
└── README.md
```

---

## Requisitos

| Requisito              | Versión Mínima                        |
|------------------------|---------------------------------------|
| Android Studio         | Hedgehog (2023.1.1) o superior        |
| JDK                    | 17                                    |
| Android SDK            | API 29 (Android 10)                   |
| Dispositivo Android    | Android 10+ con soporte USB OTG Host  |

---

## Compilación

### Opción 1: Android Studio (Recomendado)

1. Abrir Android Studio
2. `File → Open` → seleccionar la carpeta `android-app/`
3. Esperar que Gradle sincronice las dependencias
4. Conectar dispositivo Android en modo depuración USB
5. Presionar **Run** (▶) o `Shift+F10`

### Opción 2: Línea de Comandos

```bash
# Desde la carpeta android-app/
./gradlew assembleDebug

# Instalar directamente en dispositivo conectado
./gradlew installDebug

# Ejecutar pruebas unitarias
./gradlew test
```

---

## Dependencias Principales

| Librería                      | Versión  | Uso                              |
|-------------------------------|----------|----------------------------------|
| `com.github.mik3y:usb-serial-for-android` | 3.7.0 | USB CDC serial |
| `com.github.PhilJay:MPAndroidChart` | v3.1.0 | Gráfica en tiempo real |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.7.0 | MVVM |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Async |

---

## Uso de la Aplicación

### Conexión Inicial

1. Conectar el ESP32-S3 al puerto USB OTG del dispositivo Android
2. Android solicitará permiso para acceder al dispositivo USB → **Permitir**
3. La app abrirá automáticamente el puerto serie a 115200 baudios
4. El indicador de estado cambiará a **● Conectado** (verde)

### Control de Temperatura

1. Ingresar la temperatura objetivo en el campo de texto (ej: `85.0`)
2. Presionar **▶ START**
3. La app enviará:
   - `SET 85.0` → establece el setpoint en el ESP32
   - `START` → inicia el PID y el calentamiento
4. La gráfica y los valores se actualizarán en tiempo real

### Detener el Calentamiento

- Presionar **⏹ STOP**
- La app enviará `STOP` al ESP32-S3

### Reconexión USB

Si el cable se desconecta, la app intentará reconectar automáticamente cada 3 segundos. El servicio continuará corriendo en segundo plano (notificación visible).

---

## Protocolo USB CDC

Ver la documentación completa en [../../README.md](../../README.md#esquema-de-comunicación-usb).

---

## Solución de Problemas

| Síntoma                          | Solución                                            |
|----------------------------------|-----------------------------------------------------|
| No detecta el dispositivo USB    | Verificar que el cable soporte datos (no solo carga) |
| "Error de conexión" permanente   | Desconectar y reconectar el cable                   |
| La app se cierra al conectar     | Habilitar modo desarrollador y depuración USB       |
| Temperatura muestra 0.0          | Verificar conexión del sensor PT100/MAX31865        |
| Estado siempre ERROR             | Ver logs del ESP32 con `pio device monitor`         |
