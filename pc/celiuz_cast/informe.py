"""Lo que se le cuenta al usuario: el estado en palabras y lo que falta en el equipo.
Lo usan la línea de órdenes y el panel."""

import os

from . import adb, camara, sonido

SERVICIO = "celiuz-cast.service"


def ruta_apk(repo: str) -> str:
    return os.path.join(repo, "app", "celiuz-cast.apk")


def texto_estado(e: dict) -> str:
    lineas = [e.get("texto", e.get("estado", "?"))]
    cel = e.get("celular") or {}
    if e.get("estado") == "transmitiendo" and cel:
        que = f"la app {cel['app']}" if cel.get("modo") == "app" and cel.get("app") else "la pantalla entera"
        lineas.append(f"{cel.get('modelo', 'Celular')} · Android {cel.get('android', '?')} · comparte {que}")
        v = e.get("video") or {}
        if v:
            dec = {"grafica": "con la gráfica", "software": "por software"}.get(v.get("decodifica"), "")
            lineas.append(f"{v.get('ancho')}x{v.get('alto')} → cámara {v.get('salida')} · "
                          f"{e.get('fps', 0)} imágenes/s · {round((e.get('bitrate') or 0) / 1e6, 1)} Mbps · decodifica {dec}")
        if e.get("visible") is False:
            lineas.append("La app compartida no está a la vista (en el stream no se ve).")
        if e.get("aviso"):
            lineas.append(e["aviso"])
    if e.get("camara") is False:
        lineas.append(e.get("error_camara", "Falta la cámara virtual."))
    return "\n".join(lineas)


def comprobaciones(repo: str):
    """[(nombre, ok, cómo arreglarlo)]. ok: True, False o None (opcional)."""
    apk = ruta_apk(repo)
    c = []
    try:
        import gi
        gi.require_version("Gst", "1.0")
        from gi.repository import Gst
        Gst.init(None)
        faltan = [e for e in ("appsrc", "h264parse", "avdec_h264", "videoscale", "videoconvert", "appsink")
                  if not Gst.ElementFactory.find(e)]
        c.append(("GStreamer para decodificar", not faltan,
                  f"faltan {', '.join(faltan)}: instala gst-plugins-base, gst-plugins-bad y gst-libav"))
        va = all(Gst.ElementFactory.find(e) for e in ("vah264dec", "vapostproc"))
        c.append(("Decodificar con la gráfica (VA-API)", True if va else None,
                  "opcional: gst-plugin-va (Intel/AMD). Sin ella se decodifica por software"))
    except (ImportError, ValueError):
        c.append(("GStreamer para decodificar", False, "instala python-gobject y gstreamer"))
    ruta = camara.buscar()
    c.append(("Cámara virtual «Celular»", bool(ruta),
              "pasa ./instalar.sh y reinicia (o: sudo modprobe -r v4l2loopback; sudo modprobe v4l2loopback)"))
    c.append(("Micrófono virtual (pactl)", sonido.hay_pactl(), "instala pipewire-pulse"))
    c.append(("adb", adb.hay_adb(), "instala android-tools"))
    c.append(("APK de la app", os.path.exists(apk), "compílalo con app/compilar.sh"))
    unidad = os.path.expanduser("~/.config/systemd/user/" + SERVICIO)
    c.append(("Servicio celiuz-cast", os.path.exists(unidad), "pasa ./instalar.sh"))
    if adb.hay_adb():
        lista = adb.celulares()
        listos = [x for x in lista if x["estado"] == "device"]
        if any(x["estado"] == "unauthorized" for x in lista):
            c.append(("Celular", False, "acepta la depuración USB en la pantalla del celular"))
        elif not listos:
            c.append(("Celular", None, "no hay ninguno enchufado ahora (no hace falta para instalar)"))
        else:
            cel = listos[0]
            c.append((f"Celular {cel['modelo'] or cel['serie']}", True, ""))
            c.append(("App en el celular", adb.tiene_app(cel["serie"]), "celiuz-cast instalar-app"))
    return c
