#!/usr/bin/env bash
# tests/e2e/receptor.sh — el receptor entero contra un celular de mentira
# (tests/lib/celular_falso.py) con vídeo H.264 de verdad. La cámara y el
# micrófono van a ficheros; adb es un binario falso que abre el «túnel» hasta
# el celular de mentira.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/../lib/comun.sh"
preparar_entorno
CLI="$REPO/pc/celiuz-cast"
W=1920; H=1080; IMG=$((W * H * 3 / 2))

for v in "848x1920" "1920x848"; do
    ffmpeg -loglevel error -f lavfi -i "testsrc2=size=$v:rate=60" -t 2 -c:v libx264 -bf 0 -g 60 \
        -tune zerolatency -x264-params aud=1 -f h264 "$TMP/v-$v.h264" || { echo "  falta ffmpeg con libx264"; exit 1; }
done
PUERTO=$(( 40000 + RANDOM % 20000 ))

celular() {   # celular [opciones del falso]
    [ -n "${FALSO:-}" ] && kill "$FALSO" 2>/dev/null && wait "$FALSO" 2>/dev/null
    python3 "$REPO/tests/lib/celular_falso.py" "$PUERTO" "$TMP/v-848x1920.h264" "$TMP/v-1920x848.h264" "$@" > "$TMP/falso.log" 2>&1 &
    FALSO=$!; PIDS_LIMPIAR="${PIDS_LIMPIAR:-} $FALSO"
    esperar_a 5 grep -q escuchando "$TMP/falso.log"
}
recibir() {
    CELIUZ_CAST_CAMARA="$TMP/camara.raw" CELIUZ_CAST_SONIDO="$TMP/sonido.raw" "$CLI" recibir > "$TMP/receptor.log" 2>&1 &
    RECEPTOR=$!; PIDS_LIMPIAR="${PIDS_LIMPIAR:-} $RECEPTOR"
}
campo() { "$CLI" estado --json | python3 -c "import json,sys; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1" 2>/dev/null; }
estado_es() { [ "$(campo "['estado']")" = "$1" ]; }
# Lo que se espera va en funciones: `esperar_a 5 test "$(x)" ...` expandiría x una sola vez.
al_menos_conexiones() { [ "$(conexiones)" -ge "$1" ]; }
al_menos_fps() { [ "$(campo "['fps']")" -ge "$1" ] 2>/dev/null; }
al_menos_imagenes() { [ "$(imagenes)" -ge "$1" ]; }
video_es() { [ "$(campo "['video']['ancho']")x$(campo "['video']['alto']")" = "$1" ]; }
conexiones() { grep -c '^conexion' "$TMP/falso.log"; }
imagenes() { echo $(( $(stat -c%s "$TMP/camara.raw" 2>/dev/null || echo 0) / IMG )); }
# luma(n, x, y): el brillo de un punto de la imagen n de la cámara
luma() { python3 -c "
f = open('$TMP/camara.raw', 'rb'); f.seek($1 * $IMG + $3 * $W + $2); print(f.read(1)[0])"; }

# adb falso: un celular «HONOR» con la app, y el túnel lleva al de mentira.
binario_falso adb 0 '
case "$*" in
  *"devices -l"*) [ -f '"$TMP"'/sin_permiso ] && { printf "List of devices attached\nSERIE1 unauthorized usb:1-1\n"; exit 0; }
                  printf "List of devices attached\nSERIE1 device usb:1-1 model:HONOR_X7b\n" ;;
  *"pm path"*) [ -f '"$TMP"'/sin_app ] || echo "package:/data/app/x/base.apk" ;;
  *"forward tcp:0"*) echo '"$PUERTO"' ;;
esac'

titulo "1. Sin permiso y sin app, el estado lo dice"
touch "$TMP/sin_permiso"
recibir
afirmar "depuración sin aceptar: sin_permiso" esperar_a 5 estado_es sin_permiso
afirmar "y la cámara enseña la imagen de espera (del color del escritorio)" esperar_a 3 al_menos_imagenes 2
afirmar_igual "24" "$(luma 0 960 540)" "la imagen de espera es abismo (#0d0418 → Y=24)"
rm "$TMP/sin_permiso"; touch "$TMP/sin_app"
afirmar "sin la app: sin_app" esperar_a 5 estado_es sin_app
rm "$TMP/sin_app"

titulo "2. La app abierta pero sin transmitir"
celular --sin-saludo
afirmar "esperando (que pulses Transmitir)" esperar_a 15 estado_es esperando
afirmar "sigue probando, sin rendirse" esperar_a 5 al_menos_conexiones 2

titulo "3. Transmitiendo, girando y con un corte de cable"
celular --girar-tras 3 --cortar-tras 5
afirmar "transmitiendo" esperar_a 8 estado_es transmitiendo
afirmar "vídeo vertical de 848x1920" esperar_a 2 video_es 848x1920
sleep 0.3; n=$(imagenes); afirmar_igual "16" "$(luma $((n - 1)) 5 540)" "vertical: banda negra a la izquierda"
afirmar_igual "Celular de mentira" "$(campo "['celular']['modelo']")" "sabe qué celular es"
afirmar "llegan unas 60 imágenes por segundo" esperar_a 3 al_menos_fps 40
afirmar "al girar, vídeo nuevo de 1920x848" esperar_a 5 video_es 1920x848
sleep 0.4; n=$(imagenes); afirmar_igual "16" "$(luma $((n - 1)) 960 5)" "horizontal: banda negra arriba"
afirmar "la cámara sigue siendo 1920x1080 (el tamaño nunca cambia)" test $(( $(stat -c%s "$TMP/camara.raw") % IMG )) -eq 0
afirmar "tras el corte, vuelve a conectarse solo" esperar_a 8 al_menos_conexiones 2
afirmar "y vuelve a transmitir" esperar_a 5 estado_es transmitiendo
afirmar "el sonido llega" test "$(stat -c%s "$TMP/sonido.raw")" -gt 192000

titulo "4. El celular para la transmisión"
celular --fin-tras 1.5
afirmar "transmitiendo" esperar_a 8 estado_es transmitiendo
afirmar "al pulsar Detener en el celular: esperando" esperar_a 6 estado_es esperando
sleep 1.5
afirmar "y se queda esperando (el celular ya no transmite)" estado_es esperando

titulo "5. Apagar"
kill -TERM "$RECEPTOR"
afirmar "sale al pedírselo (SIGTERM)" esperar_a 5 sh -c "! kill -0 $RECEPTOR 2>/dev/null"
afirmar_igual "apagado" "$(campo "['estado']")" "y queda apagado"
afirmar "quita los túneles de adb que abrió" grep -q "forward --remove tcp:$PUERTO" "$TMP/adb.llamadas"
afirmar "sin errores en su salida" sh -c "! grep -qi -e traceback -e error '$TMP/receptor.log'"
resumen
