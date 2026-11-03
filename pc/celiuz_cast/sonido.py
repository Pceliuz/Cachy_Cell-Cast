"""El micrófono virtual «Celular»: el sonido del celular, como una entrada más.

Es un `module-pipe-source` de PipeWire (por su capa de PulseAudio): una fuente
que lee de un FIFO. OBS lo ve como un micrófono que se llama «Celular».

- Se escribe SIN bloquear: si nadie escucha (OBS cerrado), PipeWire no lee y el
  FIFO se llena; entonces el sonido se tira. Esperar sería acumular retraso.
- El FIFO es pequeño (8 KB, unos 40 ms): el retraso máximo que puede meter.
- El módulo se quita al cerrar. Si el programa murió sin quitarlo, el
  siguiente arranque lo encuentra por su nombre y lo rehace.
"""

import errno
import fcntl
import os
import shutil
import subprocess

NOMBRE = "celiuz_cast"
DESCRIPCION = "Celular"
_F_SETPIPE_SZ = 1031


def _pactl(*args):
    try:
        r = subprocess.run(["pactl", *args], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)
    return r.returncode, (r.stdout or r.stderr).strip()


def hay_pactl() -> bool:
    return shutil.which("pactl") is not None


def modulos_nuestros():
    """Los ids de los módulos pipe-source que se llaman como el nuestro."""
    codigo, salida = _pactl("list", "short", "modules")
    if codigo != 0:
        return []
    return [l.split()[0] for l in salida.splitlines()
            if "module-pipe-source" in l and f"source_name={NOMBRE}" in l]


class Microfono:
    def __init__(self, carpeta: str, frecuencia=48000, canales=2):
        self.fifo = os.path.join(carpeta, "sonido.fifo")
        self.frecuencia, self.canales = frecuencia, canales
        self.modulo = None
        self.fd = None
        self.escritos = 0
        self.tirados = 0

    def abrir(self) -> None:
        for viejo in modulos_nuestros():
            _pactl("unload-module", viejo)
        if os.path.exists(self.fifo):
            os.unlink(self.fifo)
        os.mkfifo(self.fifo, 0o600)
        codigo, salida = _pactl(
            "load-module", "module-pipe-source", f"source_name={NOMBRE}", f"file={self.fifo}",
            "format=s16le", f"rate={self.frecuencia}", f"channels={self.canales}",
            f"source_properties=device.description={DESCRIPCION}")
        if codigo != 0 or not salida.isdigit():
            raise OSError(f"no se pudo crear el micrófono virtual: {salida}")
        self.modulo = salida
        # El módulo abre el FIFO para leer: ya se puede abrir para escribir sin colgarse.
        self.fd = os.open(self.fifo, os.O_WRONLY | os.O_NONBLOCK)
        try:
            fcntl.fcntl(self.fd, _F_SETPIPE_SZ, 8192)
        except OSError:
            pass

    def escribir(self, pcm: bytes) -> None:
        if self.fd is None:
            return
        try:
            n = os.write(self.fd, pcm)
            self.escritos += 1
            if n < len(pcm):
                self.tirados += 1
        except OSError as e:
            if e.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                self.tirados += 1
            else:
                raise

    def cerrar(self) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        if self.modulo:
            _pactl("unload-module", self.modulo)
            self.modulo = None
        if os.path.exists(self.fifo):
            os.unlink(self.fifo)


class MicrofonoEnFichero:
    """Para las pruebas: el sonido acaba en un fichero normal."""

    def __init__(self, ruta: str):
        self.ruta = ruta
        self.escritos = self.tirados = 0
        self.f = None

    def abrir(self):
        self.f = open(self.ruta, "wb")

    def escribir(self, pcm: bytes):
        self.f.write(pcm)
        self.f.flush()
        self.escritos += 1

    def cerrar(self):
        if self.f:
            self.f.close()
            self.f = None
