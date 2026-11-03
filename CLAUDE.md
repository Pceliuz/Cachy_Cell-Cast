# Notas para Claude Code

Celiuz Cast: el celular como cámara y micrófono «Celular» para OBS. Es un repo
**aparte** de los dotfiles de Parzival (`Pceliuz/Celiuz_CachyOS`), en
`git@github.com:Pceliuz/Cachy_Cell-Cast.git` (antes `Celiuz-Cast-CachyOS`,
que no llegó a usarse). El usuario lo llama «Cell-Cast»; el código aún dice
«Celiuz Cast» / `celiuz-cast` (renombrarlo está sin decidir). Los dotfiles
tendrán un catálogo de apps que la instale (`CATALOGO.md` de ese repo). Lo pidió un amigo suyo que usa esa config y
hace streams jugando desde el celular (Android 13); el usuario tiene un
HONOR X7b con Android 14.

**Todo en español**: código, comentarios, mensajes y commits (presente,
describiendo el efecto). **El usuario hace él mismo los commits y el push**: se
le deja todo listo y se le dan los comandos en orden, compatibles con fish.

## Dónde va la cosa (al 2026-11-03)

**Probado en el celular de verdad el 2026-11-03** (HONOR X7b, Android 14,
con la laptop, compartiendo un juego): la app detecta la versión, comparte
una sola app, la imagen llega a OBS por la cámara «Celular» y el sonido por
el micrófono «Celular». Se grabó en OBS a 1080p60 y a 720p60. Arreglado ese
día: la cámara anunciaba 30 imágenes/s, en vertical bajaba a 448x1024, y
ahora hay «Sonido de todas las apps» (uid -1 en `CapturaAudio`).

Pendiente de aquí:
- **Comprobar «Sonido de todas las apps» en Android 14 con «una sola app»**:
  no se ha medido si Android deja oír otras apps en ese modo.
- **Que «Celular» no se rehaga en cada arranque del receptor**: el
  `module-pipe-source` se carga al empezar y se quita al salir, así que un
  reinicio cambia su id y OBS puede volver a su micrófono de antes.
- El giro en Android 13 y «No molestar» siguen sin probarse en un celular.
- Lo de abajo que siga sin marcar.

Lo de antes (2026-10-28):

Hecho y probado **en la PC**:
- El receptor (`pc/`): adb, túnel, decodificar (VA-API o software), cámara
  v4l2 de tamaño fijo con bandas, micrófono virtual, reconexión, estado.
- El panel GTK, el comando `celiuz-cast`, `instalar.sh`, el servicio.
- La app (`app/`) **compila** y su lógica pasa sus pruebas (JUnit).

**Lo que NO se ha probado todavía, y es lo siguiente:**
1. **La app en un celular de verdad.** Nunca ha corrido en el HONOR. La
   receta: `celiuz-cast instalar-app`, abrirla con `celiuz-cast abrir-app`,
   y mirar `adb logcat -s CeliuzCast`. Lo delicado: la proyección de Android
   14 (una sola app, `onCapturedContentResize`), el giro en Android 13
   (DisplayListener + `VirtualDisplay.resize`), el sonido por app
   (`addMatchingUid`) y «No molestar».
2. `./instalar.sh --sistema` en la laptop (la cámara pasa de 1 a 2
   dispositivos: video10 «Celular» y video11 «OBS»).
3. El retraso de punta a punta con un juego (en la prueba con scrcpy fue
   bueno hasta que el cable falló).
4. El icono de waybar en los dotfiles (el snippet está en el README; no se
   ha tocado el repo de los dotfiles).

## Antes de escribir nada

`./tests/run.sh` (5 pruebas: unas 70 comprobaciones; la de la app necesita
el SDK de Android y tarda ~1 min). No necesitan celular, ni OBS, ni PipeWire:
cada una monta un `$HOME` de mentira y sustituye systemctl, pactl, sudo y adb
por binarios falsos. El receptor se prueba entero contra un celular de
mentira (`tests/lib/celular_falso.py`) con H.264 de verdad.

## Privacidad: no mirar la pantalla del celular

Lo que sale en la pantalla del celular del usuario son cosas suyas
(mensajes, notificaciones). **No se capturan, no se miran y no se copian**
para comprobar nada: se comprueba por tamaños, por brillo o con la imagen de
prueba del celular de mentira. Y nunca leer la terminal de otro proceso
(`/proc/<pid>/fd/2`): se cuelga y es la del usuario.

## Las reglas (heredadas de los dotfiles)

- **Nada que dependa de la máquina entra en git**: `app/local.properties` (la
  ruta del SDK), la firma del APK (`~/.config/celiuz-cast/firma.*`, fuera del
  repo), los ajustes. `tests/unidad/portabilidad.sh` lo vigila.
- **No cablear rutas**: el comando saca la raíz de `realpath(__file__)`, el
  instalador de `readlink -f "$BASH_SOURCE"`.
- **Lo que necesita root se COPIA** desde `sistema/` con `./instalar.sh
  --sistema`; la instalación normal nunca pide contraseña.
- **El servicio se copia, no se enlaza**: `systemctl --user disable` borra una
  unidad enlazada a fuera de las carpetas de systemd.
- **adb siempre con stdin cerrado** (`adb shell` se come el de quien llama).

## Trampas medidas (no las redescubras)

- **ffmpeg leyendo H.264 de una tubería retiene las imágenes**: con
  `-fflags nobuffer -probesize 32 -flags low_delay` no sacó ni una en 4 s.
  Por eso el receptor usa GStreamer con `appsrc ... alignment=au`, que suelta
  cada imagen al decodificarla: 2 ms con VA-API, 22 ms por software (laptop,
  i5-8250U, 848x1920 → 1920x1080).
- **`vapostproc add-borders=true` NO pone bandas si la salida no lleva
  `pixel-aspect-ratio=1/1`**: cumple el tamaño estirando la imagen. Lo vigila
  `e2e/receptor.sh` (falla 2 comprobaciones sin él).
- **La salida de VA-API trae relleno al final** (3112960 bytes para un I420
  de 3110400): se recorta con los offsets/strides del `GstVideoMeta`.
- **`pipewiresink mode=provide` no crea una fuente que se vea** (probado con
  `media.class=Audio/Source/Virtual`). El micrófono es un
  `module-pipe-source` con FIFO, escrito sin bloquear: si nadie escucha,
  PipeWire no lee y el sonido se tira (esperar acumularía retraso).
- **v4l2loopback fija el formato con el primer escritor y lo mantiene
  mientras OBS lo tenga abierto.** Un vídeo que cambia de tamaño al girar
  salía rayado (prueba con scrcpy). Por eso la cámara es de tamaño fijo y el
  receptor la abre una vez y no la suelta. Si OBS se quedó con un formato
  viejo, hay que cerrarlo del todo.
- **Escribir en v4l2loopback desde Python**: `VIDIOC_S_FMT` = `0xC0D05605`
  (la `struct v4l2_format` mide 208 bytes en 64 bits, con 4 de relleno tras
  `type`), y `fcntl.ioctl(fd, req, bytearray, True)` (con `bytes` devuelve un
  int y no sirve).
- **El codificador del HONOR X7b no acepta 1080x2412** (IllegalArgumentException
  al configurar) y sí 1920 de lado mayor. La app prueba tamaños de mayor a
  menor (`Tamano.escalones`) y, en cada uno, primero con los ajustes de poco
  retraso y luego sin ellos.
- **scrcpy solo manda imágenes cuando cambia la pantalla.** La app pone
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` (100 ms): así la PC distingue «quieta» de
  «conexión muerta» (3 s sin nada).
- **Android 14 solo deja UNA `createVirtualDisplay` por proyección.** Al
  girar se hace `resize` + `setSurface` con un codificador nuevo, nunca otra
  pantalla virtual. Y el servicio en primer plano va ANTES de
  `getMediaProjection`, con `registerCallback` antes de crear la pantalla.
- **`LocalServerSocket.accept()` no se despierta al cerrar el socket en
  Android**: `Servidor.detener()` se conecta a sí mismo para despertarlo.
- **Un socket local abstracto lo puede abrir cualquier app del celular**: el
  servidor solo acepta uid 2000 (adb) o 0, por `peerCredentials`. Sin esto,
  otra app vería la pantalla.
- **`FOREGROUND_SERVICE_TYPE_MICROPHONE` no existe en Android 10** (API 30).
- **La carpeta del icono adaptable tiene que ser `mipmap-anydpi-v26`** aunque
  lint diga que sobra el `-v26` con minSdk 29: sin él, aapt no encuentra
  `@mipmap/ic_launcher`.
- **El cable USB del celular puede caerse** (error -71 en `journalctl -k`, el
  2026-10-28 con el HONOR): por eso el receptor nunca se rinde y la cámara
  enseña la última imagen 3 s y luego la de espera.
- **Con la sesión bloqueada, el Hyprland anidado de los dotfiles no pinta**
  (grim se cuelga). Para ver el panel sin abrir ventanas en el escritorio del
  usuario, se dibuja en un `Gtk.OffscreenWindow` y se guarda con
  `get_pixbuf()`.
- **v4l2loopback anuncia 30 imágenes/s si quien escribe no dice nada**, y
  OBS lee entonces a 30. `camara.py` hace `VIDIOC_S_PARM` (204 bytes) con
  1/60 al abrir; se comprueba con `v4l2-ctl -d /dev/video10 --get-parm`.
- **Las trabas en OBS casi nunca son del receptor** (2026-11-03): Hollow
  Knight en el HONOR da ~40 imágenes/s, alternando 16 y 33 ms (medido con
  `dumpsys SurfaceFlinger --latency '<capa (BLAST)>'`), y OBS estaba a 30
  FPS con salida 1092x614 (base = la pantalla de la laptop). Mirar antes
  `~/.config/obs-studio/logs/` («fps:», «output resolution») y el cable.
- **El codificador del HONOR no pasa de 1088 de alto**: en vertical
  `isSizeSupported` rechaza 1920, 1600 y 1280. Por eso el escalón 1088.
- **Con una app elegida, el sonido es SOLO de esa app** (`addMatchingUid`):
  YouTube de fondo no suena. Es lo que pide «una sola app»; el interruptor
  «Sonido de todas las apps» lo cambia.
- **«No hay sonido» en OBS suele ser que nadie lee «Celular»**: compruébalo
  con `pactl list source-outputs` (OBS sale con su `media.name` y su
  `Source:`) antes de tocar código. La fuente de PipeWire se llama
  `celiuz_cast`; «Celular» es su descripción.
- **El cable del HONOR en el puerto 1-1 de la laptop falla mucho** (error -71,
  25 en media hora, desconexiones): cortes de 2 a 5 s en la grabación.
- **`esperar_a 5 test "$(x)" ...` no espera nada** (bash expande `$(x)` una
  vez): lo que se espera va en una función. Mordió escribiendo `e2e/receptor`.

## Cómo está hecho

- `PROTOCOLO.md`: el contrato celular ↔ PC. Si lo cambias, cambia los dos
  lados y `tests/lib/celular_falso.py`.
- `app/`: Kotlin sin AndroidX (framework puro; el APK pesa ~100 KB). La lógica
  sin Android (`Forma`, `Calidad`/`Tamano`, `Cola`, `Protocolo`) tiene sus
  pruebas en `app/app/src/test`. Se compila con `app/compilar.sh`, que deja
  `app/celiuz-cast.apk` (versionado, para que quien clone no necesite el SDK).
  **Un APK firmado con otra clave no se instala encima**: compilar siempre en
  el mismo equipo o llevarse `~/.config/celiuz-cast/firma.*`.
- `pc/celiuz-cast`: el comando. `pc/celiuz_cast/`: `receptor` (el bucle),
  `video` (GStreamer), `camara` (v4l2), `sonido` (PipeWire), `adb`, `estado`
  (JSON en `$XDG_RUNTIME_DIR/celiuz-cast/`), `informe` (textos y
  comprobaciones), `panel` (GTK3), `colores` (la paleta de los dotfiles si
  está, y si no la de fábrica).
- `sistema/`: lo que `--sistema` copia a `/etc` (v4l2loopback con video10
  «Celular» y video11 «OBS», libre para la cámara virtual de OBS).
