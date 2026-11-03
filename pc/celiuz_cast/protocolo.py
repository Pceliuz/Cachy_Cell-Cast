"""Lo que viaja entre el celular y la PC. El contrato está en PROTOCOLO.md."""

import json
import socket
import struct

SALUDO = b"CCAST\x01"
CABECERA = struct.Struct(">BBqI")   # tipo, banderas, pts (µs), largo
LARGO_MAX = 1 << 20

HOLA = 1
VIDEO_CONFIG = 2
VIDEO = 3
AUDIO_CONFIG = 4
AUDIO = 5
ESTADO = 6
FIN = 7
PEDIR_CLAVE = 16

CLAVE = 1
CONFIG = 2

SOCKET = "celiuz-cast"


class Cortado(Exception):
    """La conexión se cortó o trajo algo que no es el protocolo."""


def leer_exacto(s: socket.socket, n: int) -> bytes:
    trozos = []
    while n:
        b = s.recv(min(n, 1 << 16))
        if not b:
            raise Cortado("el celular cerró la conexión")
        trozos.append(b)
        n -= len(b)
    return b"".join(trozos)


def leer_saludo(s: socket.socket) -> None:
    saludo = leer_exacto(s, len(SALUDO))
    if saludo != SALUDO:
        raise Cortado(f"no es Celiuz Cast o es otra versión: {saludo!r}")


def leer(s: socket.socket):
    """(tipo, banderas, pts, datos). Lanza Cortado o socket.timeout."""
    tipo, banderas, pts, largo = CABECERA.unpack(leer_exacto(s, CABECERA.size))
    if largo > LARGO_MAX:
        raise Cortado(f"paquete imposible: {largo} bytes")
    return tipo, banderas, pts, leer_exacto(s, largo) if largo else b""


def paquete(tipo: int, banderas: int = 0, pts: int = 0, datos: bytes = b"") -> bytes:
    return CABECERA.pack(tipo, banderas, pts, len(datos)) + datos


def json_de(datos: bytes) -> dict:
    try:
        v = json.loads(datos.decode("utf-8"))
        return v if isinstance(v, dict) else {}
    except (ValueError, UnicodeDecodeError):
        return {}
