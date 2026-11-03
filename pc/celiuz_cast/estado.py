"""El estado del receptor, en un JSON que leen el panel y la barra.

Vive en $XDG_RUNTIME_DIR/celiuz-cast/estado.json: es de esta sesión del
usuario y desaparece al apagar. Se escribe entero a un temporal y se renombra,
para que nadie lea uno a medias.
"""

import json
import os
import time


def carpeta_runtime() -> str:
    base = os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
    ruta = os.path.join(base, "celiuz-cast")
    os.makedirs(ruta, mode=0o700, exist_ok=True)
    return ruta


def ruta_estado() -> str:
    return os.path.join(carpeta_runtime(), "estado.json")


class Estado:
    def __init__(self, ruta: str):
        self.ruta = ruta
        self.datos = {"estado": "arrancando", "texto": "Arrancando…", "pid": os.getpid()}

    def poner(self, **cambios):
        for k, v in cambios.items():
            if v is None:
                self.datos.pop(k, None)
            else:
                self.datos[k] = v

    def guardar(self):
        self.datos["actualizado"] = time.time()
        tmp = self.ruta + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.datos, f, ensure_ascii=False)
        os.replace(tmp, self.ruta)


def leer(ruta: str = None, caduca_s: float = 5.0) -> dict:
    """El estado del receptor, o {"estado": "apagado"} si no hay uno vivo."""
    ruta = ruta or ruta_estado()
    try:
        with open(ruta) as f:
            datos = json.load(f)
    except (OSError, ValueError):
        return {"estado": "apagado", "texto": "Apagado"}
    vivo = datos.get("estado") != "apagado" and time.time() - datos.get("actualizado", 0) < caduca_s
    if vivo and datos.get("pid"):
        vivo = os.path.exists(f"/proc/{datos['pid']}")
    return datos if vivo else {"estado": "apagado", "texto": "Apagado"}
