"""adb: los celulares enchufados, la app y el túnel hasta ella.

Todo adb va con la entrada estándar cerrada: `adb shell` se come la de quien
lo llama (pasó en Celiuz Remoto: una contraseña leída después llegaba vacía).
"""

import shutil
import subprocess

from . import protocolo

PAQUETE = "com.celiuz.cast"


def hay_adb() -> bool:
    return shutil.which("adb") is not None


def _adb(*args, serie=None, tiempo=8):
    cmd = ["adb"] + (["-s", serie] if serie else []) + list(args)
    try:
        r = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=tiempo)
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, "", str(e)
    return r.returncode, r.stdout, r.stderr


def celulares():
    """[{serie, estado, modelo}] — estado: device, unauthorized, offline..."""
    codigo, salida, _ = _adb("devices", "-l")
    if codigo != 0:
        return []
    lista = []
    for linea in salida.splitlines()[1:]:
        partes = linea.split()
        if len(partes) < 2:
            continue
        extra = dict(p.split(":", 1) for p in partes[2:] if ":" in p)
        lista.append({
            "serie": partes[0],
            "estado": partes[1],
            "modelo": extra.get("model", "").replace("_", " "),
        })
    return lista


def tiene_app(serie: str) -> bool:
    codigo, salida, _ = _adb("shell", "pm", "path", PAQUETE, serie=serie)
    return codigo == 0 and "package:" in salida


def tunel(serie: str) -> int:
    """Abre `adb forward` hasta el socket de la app. Devuelve el puerto local."""
    codigo, salida, error = _adb("forward", "tcp:0", f"localabstract:{protocolo.SOCKET}", serie=serie)
    if codigo != 0 or not salida.strip().isdigit():
        raise OSError(f"adb forward falló: {error.strip() or salida.strip()}")
    return int(salida.strip())


def quitar_tunel(serie: str, puerto: int) -> None:
    _adb("forward", "--remove", f"tcp:{puerto}", serie=serie)


def abrir_app(serie: str) -> bool:
    codigo, _, _ = _adb("shell", "am", "start", "-n", f"{PAQUETE}/.MainActivity", serie=serie)
    return codigo == 0


def instalar(serie: str, apk: str):
    """(ok, mensaje). -r reemplaza la que hubiera, -d deja bajar de versión."""
    codigo, salida, error = _adb("install", "-r", "-d", apk, serie=serie, tiempo=120)
    texto = (salida + error).strip().splitlines()
    return codigo == 0 and "Success" in salida, (texto[-1] if texto else "")
