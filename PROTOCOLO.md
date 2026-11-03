# El contrato entre el celular y la PC

Lo que viaja entre la app (`app/`) y el receptor (`pc/`). Si cambias algo aquí,
cambia los dos lados **y** el celular de mentira de las pruebas
(`tests/lib/celular_falso.py`), que es quien lo vigila.

## La conexión

- El celular escucha en un **socket local abstracto** llamado `celiuz-cast`.
  No abre ningún puerto de red.
- La PC llega a él por adb: `adb -s <serie> forward tcp:0 localabstract:celiuz-cast`
  devuelve un puerto de la PC (solo en `127.0.0.1`) y se conecta ahí.
  Por wifi es lo mismo: con la «Depuración inalámbrica» emparejada, adb hace el
  mismo túnel.
- **El celular solo acepta conexiones de adb** (uid 2000, `shell`, o 0, `root`),
  mirando las credenciales del socket. Cualquier otra app del celular podría
  conectarse a un socket local; sin esta comprobación podría ver tu pantalla.
- Un cliente a la vez: si llega otro, el anterior se cierra.

## El formato

Al conectar, el celular manda 6 bytes: `CCAST` y la versión (`0x01`).

Después, paquetes en los dos sentidos, con una cabecera de 14 bytes
(**big-endian**):

| Campo | Tamaño | Qué es |
|---|---|---|
| tipo | 1 | ver tablas |
| banderas | 1 | depende del tipo |
| pts | 8 | marca de tiempo en microsegundos (la del celular) |
| largo | 4 | bytes que siguen |

### Del celular a la PC

| Tipo | Nombre | Contenido |
|---|---|---|
| 1 | `HOLA` | JSON: `android` (texto, "14"), `sdk` (34), `modelo`, `modo` (`app` o `pantalla`), `app` (nombre o `null`), `audio` (bool) |
| 2 | `VIDEO_CONFIG` | JSON: `ancho`, `alto`, `codec` (`h264`). **Empieza un vídeo nuevo**: la PC rehace el decodificador. Se manda al conectar y cada vez que cambia el tamaño (al girar el celular o al cambiar la app de tamaño) |
| 3 | `VIDEO` | Una imagen completa en H.264 Annex-B. Banderas: `1` = imagen clave, `2` = configuración (SPS/PPS) |
| 4 | `AUDIO_CONFIG` | JSON: `frecuencia` (48000), `canales` (2), `formato` (`s16le`) |
| 5 | `AUDIO` | PCM tal cual, 10 ms por paquete |
| 6 | `ESTADO` | JSON: `visible` (bool, la app compartida está a la vista), `bitrate` (bps actuales), `aviso` (texto o ausente) |
| 7 | `FIN` | El usuario paró la transmisión en el celular |

Tras `VIDEO_CONFIG` llega siempre la configuración (`VIDEO` con bandera 2) y
luego una imagen clave: **el celular no manda imágenes intermedias hasta que
haya una clave**, porque sin ella la PC solo puede pintar basura.

### De la PC al celular

| Tipo | Nombre | Contenido |
|---|---|---|
| 16 | `PEDIR_CLAVE` | vacío: la PC perdió el hilo y quiere una imagen clave |

## Lo que pasa si la red no da para más

Lo decide el celular, que es quien ve la cola:

- Si lo que espera para salir pasa de **250 ms** de vídeo, tira las imágenes
  que esperan, pide una clave al codificador y baja el bitrate un 30 %.
- Cada 5 s sin atascos lo vuelve a subir un 15 %, hasta el de la calidad
  elegida.
- El audio no se tira por atascos de vídeo; solo si él mismo se acumula
  (más de 200 ms), y entonces se tira el más viejo.

El codificador repite la última imagen cada 100 ms aunque la pantalla esté
quieta. Así la PC recibe algo siempre y distingue «pantalla quieta» de
«conexión muerta» (más de 3 s sin nada).
