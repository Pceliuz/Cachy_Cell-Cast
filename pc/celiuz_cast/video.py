"""Decodifica el H.264 del celular y lo deja del tamaño de la cámara, con bandas.

Con GStreamer y no con ffmpeg por tubería: ffmpeg leyendo de una tubería
retiene las imágenes (medido: ni una en 4 s), y GStreamer acepta cada imagen
completa (`alignment=au`) y la suelta en cuanto la decodifica.

Decodifica con la gráfica (VA-API: Intel y AMD) si puede —medido en la
laptop: 2 ms por imagen contra 22 ms por software— y si no, o si la gráfica
falla a mitad, por software.
"""

import threading

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstVideo", "1.0")
from gi.repository import Gst, GstVideo  # noqa: E402

Gst.init(None)


def puede_grafica() -> bool:
    return all(Gst.ElementFactory.find(e) for e in ("vah264dec", "vapostproc"))


def _cadena(tipo: str, ancho: int, alto: int) -> str:
    # pixel-aspect-ratio=1/1 es lo que obliga a poner BANDAS: sin él, la gráfica
    # cumple el tamaño estirando la imagen (píxeles no cuadrados).
    salida = f"video/x-raw,format=I420,width={ancho},height={alto},pixel-aspect-ratio=1/1"
    if tipo == "grafica":
        medio = f"vah264dec ! vapostproc add-borders=true ! {salida}"
    else:
        medio = f"avdec_h264 ! videoscale add-borders=true ! videoconvert ! {salida}"
    return ("appsrc name=entrada is-live=true do-timestamp=true format=time "
            "caps=video/x-h264,stream-format=byte-stream,alignment=au ! "
            f"h264parse ! {medio} ! appsink name=salida sync=false max-buffers=2 drop=true emit-signals=true")


class Decodificador:
    """Empuja imágenes H.264 con `empujar`; cada imagen lista llega a `al_imagen(bytes)`."""

    def __init__(self, ancho: int, alto: int, al_imagen, preferir_grafica=True):
        self.ancho, self.alto = ancho, alto
        self.al_imagen = al_imagen
        self.tamano = ancho * alto * 3 // 2
        self.tipo = "grafica" if preferir_grafica and puede_grafica() else "software"
        self.decodificadas = 0
        self.errores = 0
        self._cerrojo = threading.Lock()
        self._montar()

    def _montar(self):
        self.tubo = Gst.parse_launch(_cadena(self.tipo, self.ancho, self.alto))
        self.entrada = self.tubo.get_by_name("entrada")
        self.tubo.get_by_name("salida").connect("new-sample", self._muestra)
        self.bus = self.tubo.get_bus()
        self.tubo.set_state(Gst.State.PLAYING)

    def _muestra(self, sink):
        muestra = sink.emit("pull-sample")
        if muestra is None:
            return Gst.FlowReturn.OK
        buf = muestra.get_buffer()
        ok, mapa = buf.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.OK
        try:
            imagen = self._contigua(bytes(mapa.data), GstVideo.buffer_get_video_meta(buf))
        finally:
            buf.unmap(mapa)
        if imagen is not None:
            self.decodificadas += 1
            self.al_imagen(imagen)
        return Gst.FlowReturn.OK

    def _contigua(self, datos: bytes, meta):
        """La imagen I420 sin relleno entre filas ni entre planos."""
        w, h = self.ancho, self.alto
        normal = ([0, w * h, w * h * 5 // 4], [w, w // 2, w // 2])
        if meta is None or (list(meta.offset)[:3], list(meta.stride)[:3]) == normal:
            return datos[:self.tamano] if len(datos) >= self.tamano else None
        partes = []
        for plano, (pw, ph) in enumerate([(w, h), (w // 2, h // 2), (w // 2, h // 2)]):
            ini, paso = meta.offset[plano], meta.stride[plano]
            partes.extend(datos[ini + f * paso: ini + f * paso + pw] for f in range(ph))
        return b"".join(partes)

    def empujar(self, imagen: bytes) -> None:
        with self._cerrojo:
            self._revisar_errores()
            self.entrada.emit("push-buffer", Gst.Buffer.new_wrapped(imagen))

    def _revisar_errores(self):
        msg = self.bus.pop_filtered(Gst.MessageType.ERROR)
        if msg is None:
            return
        self.errores += 1
        err, _ = msg.parse_error()
        if self.tipo == "grafica":
            # La gráfica falló (driver, tamaño raro): se sigue por software.
            self.tubo.set_state(Gst.State.NULL)
            self.tipo = "software"
            self._montar()
        else:
            raise RuntimeError(f"el decodificador falló: {err.message}")

    def cerrar(self) -> None:
        with self._cerrojo:
            self.tubo.set_state(Gst.State.NULL)
