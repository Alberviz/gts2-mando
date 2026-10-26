# gts2-mando

App Android mínima de uso personal: publica una sesión de música falsa para que la pantalla de música de un reloj (probado con Amazfit GTS 2 + Zepp) funcione como mando de 3 botones.

- ⏯ `POST {hubUrl}/api/mando/creatina` (Bearer `hubToken`)
- ⏭ `GET {pingUrl}/api/widget/ping?token={pingToken}&user={pingUser}`
- ⏮ graba audio (OGG/Opus, 2.ª pulsación o 20 s) y lo envía a `POST {hubUrl}/api/mando/comida`

El título de la «canción» muestra el estado. Los servidores no están en este repo.

## Compilar
```bash
export JAVA_HOME=<jdk 21> ANDROID_HOME=<sdk>
./gradlew assembleDebug
```

## Configurar
Rellena los 5 campos a mano o copia al portapapeles líneas `clave=valor` (`hubUrl=…`, `hubToken=…`, `pingUrl=…`, `pingToken=…`, `pingUser=…`) y pulsa «Pegar config». La app reproduce silencio continuo: sin audio real Android no le entrega los botones del reloj.

Arranque: ajuste rápido «Mando» (cortina de notificaciones), aviso «toca para activar» al encender el móvil (Android 15+ no deja arrancarlo solo; en versiones anteriores arranca solo) y Android lo relanza si lo cierra.

Depurar: `adb logcat -s mando` · simular botones: `adb shell cmd media_session dispatch next|play-pause|previous`.
