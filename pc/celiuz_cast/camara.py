"""La cámara virtual «Celular» (v4l2loopback), escrita imagen a imagen.

El tamaño de la cámara es FIJO (1920x1080 por defecto) y la imagen del
celular entra con bandas. Es lo que evita la imagen rayada de la primera
prueba: v4l2loopback fija el formato con el primer programa que escribe y lo
mantiene mientras OBS lo tenga abierto, así que un vídeo que cambia de tamaño
al girar el celular salía hecho tiras.

Las imágenes van en I420 (YUV 4:2:0 planar), que es lo que OBS lee sin
convertir.
"""

import fcntl
import glob
import os
import stat
import struct

NOMBRE = "Celular"
_VIDIOC_S_FMT = 0xC0D05605          # _IOWR('V', 5, struct v4l2_format), 208 bytes en 64 bits
_VIDIOC_S_PARM = 0xC0CC5616         # _IOWR('V', 22, struct v4l2_streamparm), 204 bytes
_SALIDA = 2                         # V4L2_BUF_TYPE_VIDEO_OUTPUT
_CAMPO_NINGUNO = 1                  # V4L2_FIELD_NONE
_SRGB = 8                           # V4L2_COLORSPACE_SRGB
_I420 = int.from_bytes(b"YU12", "little")
# Las imágenes por segundo que la cámara ANUNCIA a quien la lee. v4l2loopback
# anuncia 30 si nadie dice nada, y OBS lee entonces a 30: con un juego a 40
# se saltaba unas imágenes y repetía otras (trabas medidas el 2026-11-03).
# Anunciar de más no cuesta nada: OBS repite la última si no llega otra.
FPS = 60


def buscar(nombre: str = NOMBRE, raiz: str = None):
    """El /dev/videoN cuya etiqueta es `nombre`, o None."""
    raiz = raiz or os.environ.get("CELIUZ_CAST_V4L2", "/sys/class/video4linux")
    for d in sorted(glob.glob(os.path.join(raiz, "video*"))):
        try:
            with open(os.path.join(d, "name")) as f:
                if f.read().strip() == nombre:
                    return "/dev/" + os.path.basename(d)
        except OSError:
            continue
    return None


def color_yuv(hexa: str):
    """'#0d0418' -> (y, u, v) de rango limitado (BT.601), como lo lee OBS."""
    r, g, b = (int(hexa.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
    y = 16 + (65.481 * r + 128.553 * g + 24.966 * b) / 255
    u = 128 + (-37.797 * r - 74.203 * g + 112.0 * b) / 255
    v = 128 + (112.0 * r - 93.786 * g - 18.214 * b) / 255
    return round(y), round(u), round(v)


class Camara:
    def __init__(self, ruta: str, ancho: int, alto: int):
        if ancho % 2 or alto % 2:
            raise ValueError("el tamaño de la cámara tiene que ser par")
        self.ruta, self.ancho, self.alto = ruta, ancho, alto
        self.tamano = ancho * alto * 3 // 2
        # Una ruta que no es un dispositivo (las pruebas) recibe las imágenes tal cual.
        es_dispositivo = os.path.exists(ruta) and stat.S_ISCHR(os.stat(ruta).st_mode)
        self.fd = os.open(ruta, os.O_RDWR if es_dispositivo else os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        if es_dispositivo:
            fmt = bytearray(struct.pack("<I4x12I", _SALIDA, ancho, alto, _I420, _CAMPO_NINGUNO,
                                        ancho, self.tamano, _SRGB, 0, 0, 0, 0, 0).ljust(208, b"\0"))
            fcntl.ioctl(self.fd, _VIDIOC_S_FMT, fmt, True)
            # v4l2_outputparm: capability, outputmode, timeperframe (num/den)
            parm = bytearray(struct.pack("<IIIII", _SALIDA, 0, 0, 1, FPS).ljust(204, b"\0"))
            try:
                fcntl.ioctl(self.fd, _VIDIOC_S_PARM, parm, True)
            except OSError:
                pass        # una cámara que no lo admite sigue valiendo, a 30
        self.escritas = 0

    def escribir(self, imagen: bytes) -> None:
        if len(imagen) != self.tamano:
            raise ValueError(f"imagen de {len(imagen)} bytes, la cámara espera {self.tamano}")
        os.write(self.fd, imagen)
        self.escritas += 1

    def lisa(self, hexa: str = "#0d0418") -> bytes:
        """Una imagen de un solo color (la de espera)."""
        y, u, v = color_yuv(hexa)
        n = self.ancho * self.alto
        return bytes([y]) * n + bytes([u]) * (n // 4) + bytes([v]) * (n // 4)

    def cerrar(self) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
