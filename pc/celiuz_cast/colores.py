"""La paleta del panel: la del escritorio Celiuz si está, y si no, la de fábrica.

Se lee en caliente de ~/.config/hypr/conf/colores.conf (el enlace que crean
los dotfiles), así que si cambias el color del escritorio, el panel lo sigue.
Recortando el alfa: rgba(b16cffff) -> #b16cff.
"""

import os
import re

FABRICA = {
    "abismo": "#0d0418", "superficie": "#1a0830", "apagado": "#2d1b4e", "violeta": "#6a00f4",
    "amatista": "#b16cff", "neon": "#c77dff", "luz": "#e4c7ff", "tenue": "#8a7aa8",
    "alerta": "#eb6f92", "atencion": "#f6c177", "exito": "#7dffb0",
}


def paleta(ruta: str = None) -> dict:
    ruta = ruta or os.path.expanduser("~/.config/hypr/conf/colores.conf")
    colores = dict(FABRICA)
    try:
        with open(ruta) as f:
            for linea in f:
                m = re.match(r"\s*\$(\w+)\s*=\s*rgba\(([0-9a-fA-F]{6})[0-9a-fA-F]{2}\)", linea)
                if m and m.group(1) in colores:
                    colores[m.group(1)] = "#" + m.group(2).lower()
    except OSError:
        pass
    return colores
