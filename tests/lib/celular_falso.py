#!/usr/bin/env python3
"""Un celular de mentira: habla el protocolo de PROTOCOLO.md por TCP.

  celular_falso.py <puerto> <h264 vertical> <h264 horizontal> [opciones]

  --girar-tras S     a los S segundos cambia al vídeo horizontal (VIDEO_CONFIG nuevo)
  --cortar-tras S    a los S segundos corta la conexión a lo bruto (un cable que falla)
  --fin-tras S       a los S segundos manda FIN (el usuario pulsó Detener)
  --sin-saludo       acepta y cierra sin decir nada (la app no está transmitiendo)

Los .h264 son Annex-B con AUD (x264 con aud=1). Apunta en stdout una línea por
conexión y por cada PEDIR_CLAVE recibido.
"""

import json
import math
import re
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, __file__.rsplit("/tests/", 1)[0] + "/pc")
from celiuz_cast import protocolo as P  # noqa: E402


def imagenes(ruta):
    datos = open(ruta, "rb").read()
    pos = [m.start() for m in re.finditer(rb"\x00\x00\x00\x01\x09", datos)]
    aus = [datos[a:b] for a, b in zip(pos, pos[1:] + [len(datos)])]
    salida = []
    for au in aus:
        nals = re.split(rb"(?=\x00\x00\x00\x01|(?<!\x00)\x00\x00\x01)", au)
        tipos = [n[n.index(b"\x01") + 1] & 0x1f for n in nals if b"\x01" in n[:4]]
        config = b"".join(n for n in nals if b"\x01" in n[:4] and (n[n.index(b"\x01") + 1] & 0x1f) in (7, 8))
        salida.append((5 in tipos, config, au))
    return salida


def tamano_de(ruta):
    m = re.search(r"(\d+)x(\d+)", ruta)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


terminado = [False]


def servir(puerto, vertical, horizontal, opciones):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", puerto))
    srv.listen(2)
    print("escuchando", flush=True)
    conexiones = 0
    terminado[0] = False
    while True:
        c, _ = srv.accept()
        conexiones += 1
        print(f"conexion {conexiones}", flush=True)
        if "--sin-saludo" in opciones or terminado[0]:
            c.close()
            continue
        try:
            sesion(c, vertical, horizontal, opciones)
        except OSError:
            pass
        finally:
            c.close()


def sesion(c, vertical, horizontal, opciones):
    vivo = [True]

    def leer():
        try:
            while vivo[0]:
                tipo, _, _, _ = P.leer(c)
                if tipo == P.PEDIR_CLAVE:
                    print("pedir_clave", flush=True)
        except (P.Cortado, OSError):
            vivo[0] = False
    threading.Thread(target=leer, daemon=True).start()

    def j(**d):
        return json.dumps(d).encode()
    c.sendall(P.SALUDO)
    c.sendall(P.paquete(P.HOLA, datos=j(android="14", sdk=34, modelo="Celular de mentira", modo="app",
                                        app="Juego", audio=True)))
    c.sendall(P.paquete(P.AUDIO_CONFIG, datos=j(frecuencia=48000, canales=2, formato="s16le")))
    t0 = time.monotonic()
    girar = float(opciones.get("--girar-tras", 1e9))
    cortar = float(opciones.get("--cortar-tras", 1e9))
    fin = float(opciones.get("--fin-tras", 1e9))
    actual, girado, n, muestra = None, False, 0, 0
    while vivo[0]:
        t = time.monotonic() - t0
        if t > cortar:
            c.shutdown(socket.SHUT_RDWR)
            return
        if t > fin:
            c.sendall(P.paquete(P.FIN))
            terminado[0] = True        # como la app: tras Detener ya no escucha
            time.sleep(0.2)
            return
        quiero = horizontal if t > girar else vertical
        if quiero is not actual:
            actual, imgs, n = quiero, quiero[1], 0
            w, h = tamano_de(quiero[0])
            c.sendall(P.paquete(P.VIDEO_CONFIG, datos=j(ancho=w, alto=h, codec="h264")))
            config = next(cfg for clave, cfg, _ in imgs if cfg)
            c.sendall(P.paquete(P.VIDEO, P.CONFIG, 0, config))
        clave, _, au = imgs[n % len(imgs)]
        n += 1
        pts = int(t * 1e6)
        c.sendall(P.paquete(P.VIDEO, P.CLAVE if clave else 0, pts, au))
        # ~16 ms de sonido por imagen (dos trozos de 10 ms cada tanto)
        for _ in range(2 if n % 3 else 1):
            pcm = b"".join(struct.pack("<hh", *(2 * [int(6000 * math.sin(2 * math.pi * 440 * (muestra + k) / 48000))]))
                           for k in range(480))
            muestra += 480
            c.sendall(P.paquete(P.AUDIO, 0, pts, pcm))
        if n % 30 == 0:
            c.sendall(P.paquete(P.ESTADO, datos=j(visible=True, bitrate=8_000_000)))
        time.sleep(1 / 60)


if __name__ == "__main__":
    puerto, v, h = int(sys.argv[1]), sys.argv[2], sys.argv[3]
    args = sys.argv[4:]
    opciones = {}
    i = 0
    while i < len(args):
        if args[i] == "--sin-saludo":
            opciones[args[i]] = True
            i += 1
        else:
            opciones[args[i]] = args[i + 1]
            i += 2
    servir(puerto, (v, imagenes(v)), (h, imagenes(h)), opciones)
