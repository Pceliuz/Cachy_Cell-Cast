#!/usr/bin/env bash
# tests/unidad/piezas.sh — las piezas del receptor por separado: la cámara, el
# protocolo, el estado, la barra, los ajustes y la paleta.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/../lib/comun.sh"
preparar_entorno
CLI="$REPO/pc/celiuz-cast"
py() { python3 -c "import sys; sys.path.insert(0, '$REPO/pc'); $1"; }

titulo "1. La cámara"
mkdir -p "$TMP/sys/video0" "$TMP/sys/video10"
echo "EasyCamera" > "$TMP/sys/video0/name"; echo "Celular" > "$TMP/sys/video10/name"
afirmar_igual "/dev/video10" "$(py "from celiuz_cast import camara; print(camara.buscar(raiz='$TMP/sys'))")" \
    "encuentra «Celular» por su nombre, no por su número"
afirmar_igual "None" "$(py "from celiuz_cast import camara; print(camara.buscar('Otra', raiz='$TMP/sys'))")" \
    "sin ella, None"
afirmar_igual "(16, 128, 128)" "$(py "from celiuz_cast import camara; print(camara.color_yuv('#000000'))")" \
    "el negro es 16/128/128 (rango limitado, lo que lee OBS)"
afirmar_igual "6220800" "$(py "
from celiuz_cast import camara
c = camara.Camara('$TMP/cam.raw', 1920, 1080); c.escribir(c.lisa()); c.escribir(c.lisa()); c.cerrar()
import os; print(os.path.getsize('$TMP/cam.raw'))")" "escribe imágenes I420 enteras de 1920x1080"
afirmar "una imagen de otro tamaño se rechaza" \
    py "from celiuz_cast import camara
c = camara.Camara('$TMP/cam2.raw', 640, 480)
try: c.escribir(b'x' * 10)
except ValueError: sys.exit(0)
sys.exit(1)"

titulo "2. El protocolo"
afirmar_igual "(3, 1, 123456789, b'abc')" "$(py "
import socket
from celiuz_cast import protocolo as P
a, b = socket.socketpair()
a.sendall(P.paquete(P.VIDEO, P.CLAVE, 123456789, b'abc'))
print(P.leer(b))")" "un paquete de ida y vuelta (cabecera de 14 bytes, big-endian)"
afirmar "un saludo que no es Celiuz Cast se rechaza" py "
import socket
from celiuz_cast import protocolo as P
a, b = socket.socketpair(); a.sendall(b'HTTP/1')
try: P.leer_saludo(b)
except P.Cortado: sys.exit(0)
sys.exit(1)"
afirmar "un paquete imposible (más de 1 MB) corta" py "
import socket, struct
from celiuz_cast import protocolo as P
a, b = socket.socketpair(); a.sendall(P.CABECERA.pack(3, 0, 0, 50_000_000))
try: P.leer(b)
except P.Cortado: sys.exit(0)
sys.exit(1)"

titulo "3. El estado y la barra"
afirmar_igual "apagado" "$("$CLI" estado --json | py "import json; print(json.load(sys.stdin)['estado'])")" \
    "sin receptor: apagado"
afirmar_igual '{"text": "", "class": "apagado"}' "$("$CLI" barra)" "la barra se esconde si está apagado"
mkdir -p "$XDG_RUNTIME_DIR/celiuz-cast"
py "
import json, os, time
json.dump({'estado': 'transmitiendo', 'texto': 'Transmitiendo', 'pid': os.getppid(), 'actualizado': time.time(),
           'celular': {'modelo': 'HONOR X7b', 'android': '14', 'modo': 'app', 'app': 'Juego'}},
          open('$XDG_RUNTIME_DIR/celiuz-cast/estado.json', 'w'))"
afirmar_igual "transmitiendo" "$("$CLI" barra | py "import json; print(json.load(sys.stdin)['class'])")" \
    "transmitiendo: la barra lo dice"
afirmar "el texto dice qué celular y qué app" sh -c "'$CLI' estado | grep -q 'HONOR X7b · Android 14 · comparte la app Juego'"
py "
import json
d = json.load(open('$XDG_RUNTIME_DIR/celiuz-cast/estado.json')); d['pid'] = 999999999
json.dump(d, open('$XDG_RUNTIME_DIR/celiuz-cast/estado.json', 'w'))"
afirmar_igual "apagado" "$("$CLI" estado --json | py "import json; print(json.load(sys.stdin)['estado'])")" \
    "un estado de un receptor muerto no cuenta"

titulo "4. Ajustes y paleta"
afirmar_igual "(1280, 720) (1920, 1080) (1920, 1080)" "$(py "
from celiuz_cast.receptor import tamano_salida as t
print(t({'salida': '1280x720'}), t({'salida': 'mucho'}), t({'salida': '1921x1080'}))")" \
    "salida = AxB, y si no vale, 1920x1080"
mkdir -p "$TMP/hypr"; echo '$amatista = rgba(00ff00ff)' > "$TMP/hypr/colores.conf"
afirmar_igual "#00ff00 #0d0418" "$(py "
from celiuz_cast import colores; p = colores.paleta('$TMP/hypr/colores.conf'); print(p['amatista'], p['abismo'])")" \
    "la paleta sale de la del escritorio, y lo que no esté, de fábrica"

resumen
