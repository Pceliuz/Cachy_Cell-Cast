"""El panel de Celiuz Cast: encender, ver qué pasa e instalar la app.

Una ventana GTK3 normal (clase `celiuz-cast`). Lee el estado del receptor del
JSON que este escribe (estado.py) cada segundo, y repasa lo que falta en el
equipo (adb, la cámara, la app...) cada pocos segundos en otro hilo, porque
preguntar a adb tarda.
"""

import os
import subprocess
import sys
import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

from . import colores, estado, informe  # noqa: E402

CSS = """
window {{ background: {abismo}; }}
* {{ color: {luz}; font-size: 14px; }}
.titulo {{ font-size: 22px; font-weight: bold; }}
.sub {{ color: {tenue}; font-size: 13px; }}
.tarjeta {{ background: {superficie}; border: 1px solid {apagado}; border-radius: 14px; padding: 14px; }}
.estado {{ font-size: 17px; font-weight: bold; }}
.transmitiendo {{ color: {exito}; }}
.esperando, .reconectando, .arrancando {{ color: {atencion}; }}
.sin_celular, .sin_permiso, .sin_app, .sin_adb {{ color: {alerta}; }}
.apagado {{ color: {tenue}; }}
.detalle {{ color: {tenue}; font-size: 13px; }}
.seccion {{ color: {tenue}; font-size: 11px; font-weight: bold; letter-spacing: 1px; }}
.bien {{ color: {exito}; }}
.falta {{ color: {alerta}; }}
.opcional {{ color: {tenue}; }}
button {{ background: {apagado}; border: none; border-radius: 10px; padding: 8px 14px; box-shadow: none; }}
button:hover {{ background: {violeta}; }}
switch {{ background: {apagado}; border: none; }}
switch:checked {{ background: {violeta}; }}
switch slider {{ background: {luz}; }}
switch image {{ color: transparent; }}
"""


class Panel(Gtk.Window):
    def __init__(self, repo: str):
        super().__init__(title="Celiuz Cast")
        self.repo = repo
        self.cli = os.path.join(repo, "pc", "celiuz-cast")
        self.set_default_size(470, -1)
        self.set_resizable(False)
        self.set_border_width(18)
        prov = Gtk.CssProvider()
        prov.load_from_data(CSS.format(**colores.paleta()).encode())
        Gtk.StyleContext.add_provider_for_screen(self.get_screen(), prov,
                                                 Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        caja = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        self.add(caja)

        cabeza = Gtk.Box(spacing=12)
        textos = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        textos.pack_start(self._etiqueta("Celiuz Cast", "titulo"), False, False, 0)
        textos.pack_start(self._etiqueta("El celular como cámara y micrófono para OBS", "sub"), False, False, 0)
        cabeza.pack_start(textos, True, True, 0)
        self.interruptor = Gtk.Switch(valign=Gtk.Align.CENTER)
        self.interruptor.set_tooltip_text("Recibir el celular")
        self._cambiando = False
        self.interruptor.connect("state-set", self._al_interruptor)
        cabeza.pack_end(self.interruptor, False, False, 0)
        caja.pack_start(cabeza, False, False, 0)

        tarjeta = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        tarjeta.get_style_context().add_class("tarjeta")
        self.estado = self._etiqueta("", "estado")
        self.detalle = self._etiqueta("", "detalle")
        tarjeta.pack_start(self.estado, False, False, 0)
        tarjeta.pack_start(self.detalle, False, False, 0)
        caja.pack_start(tarjeta, False, False, 0)

        botones = Gtk.Box(spacing=8, homogeneous=True)
        self.boton_instalar = Gtk.Button(label="Instalar en el celular")
        self.boton_instalar.connect("clicked", lambda *_: self._orden("instalar-app"))
        self.boton_abrir = Gtk.Button(label="Abrir la app en el celular")
        self.boton_abrir.connect("clicked", lambda *_: self._orden("abrir-app"))
        botones.pack_start(self.boton_instalar, True, True, 0)
        botones.pack_start(self.boton_abrir, True, True, 0)
        caja.pack_start(botones, False, False, 0)
        self.resultado = self._etiqueta("", "detalle")
        self.resultado.set_no_show_all(True)     # sin texto, no ocupa sitio
        caja.pack_start(self.resultado, False, False, 0)

        caja.pack_start(self._etiqueta("EN OBS", "seccion"), False, False, 0)
        caja.pack_start(self._etiqueta(
            "Imagen: Fuente → «Dispositivo de captura de vídeo (V4L2)» → «Celular».\n"
            "Sonido: Fuente → «Captura de entrada de audio» → «Celular».\n"
            "Si la imagen sale rayada, cierra OBS del todo y ábrelo otra vez.", "detalle"), False, False, 0)

        caja.pack_start(self._etiqueta("ESTE EQUIPO", "seccion"), False, False, 0)
        self.lista = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        caja.pack_start(self.lista, False, False, 0)

        self.connect("destroy", Gtk.main_quit)
        self._refrescar()
        GLib.timeout_add(1000, self._refrescar)
        self._repasar()
        GLib.timeout_add_seconds(5, self._repasar)

    def _etiqueta(self, texto, clase=None):
        e = Gtk.Label(label=texto, xalign=0)
        e.set_line_wrap(True)
        e.set_max_width_chars(52)   # sin esto, GTK3 pide el ancho de la frase entera
        if clase:
            e.get_style_context().add_class(clase)
        return e

    # --- Estado -----------------------------------------------------------

    def _refrescar(self):
        e = estado.leer()
        ctx = self.estado.get_style_context()
        for c in list(ctx.list_classes()):
            if c != "estado":
                ctx.remove_class(c)
        ctx.add_class(e.get("estado", "apagado"))
        texto = informe.texto_estado(e)
        primera, _, resto = texto.partition("\n")
        self.estado.set_text(("● " if e.get("estado") == "transmitiendo" else "") + primera)
        apagado = e.get("estado") == "apagado"
        self.detalle.set_text(resto if not apagado else
                              "Enciende el interruptor para que el celular aparezca como cámara «Celular».")
        if not self._cambiando:
            self._cambiando = True
            self.interruptor.set_active(not apagado)
            self._cambiando = False
        return True

    def _al_interruptor(self, _sw, encendido):
        if self._cambiando:
            return False
        self._orden("encender" if encendido else "apagar")
        return False

    # --- Lo que falta en el equipo ---------------------------------------

    def _repasar(self):
        def trabajo():
            try:
                lista = informe.comprobaciones(self.repo)
            except Exception as ex:  # el panel no debe caerse por un repaso
                lista = [("Repaso", False, str(ex))]
            GLib.idle_add(self._pintar_lista, lista)
        threading.Thread(target=trabajo, daemon=True).start()
        return True

    def _pintar_lista(self, lista):
        for hijo in self.lista.get_children():
            self.lista.remove(hijo)
        for nombre, ok, arreglo in lista:
            fila = Gtk.Box(spacing=8)
            marca = self._etiqueta({True: "bien", False: "falta", None: "  -  "}[ok],
                                   {True: "bien", False: "falta", None: "opcional"}[ok])
            marca.set_width_chars(6)
            fila.pack_start(marca, False, False, 0)
            fila.pack_start(self._etiqueta(nombre + ("" if ok is True or not arreglo else f" — {arreglo}"),
                                           None if ok is not None else "detalle"), True, True, 0)
            self.lista.pack_start(fila, False, False, 0)
        self.lista.show_all()
        return False

    # --- Órdenes ----------------------------------------------------------

    def _decir(self, texto):
        self.resultado.set_text(texto)
        self.resultado.set_visible(bool(texto))
        return False

    def _orden(self, orden):
        self._decir("…")

        def trabajo():
            r = subprocess.run([sys.executable, self.cli, orden], stdin=subprocess.DEVNULL,
                               capture_output=True, text=True)
            texto = (r.stdout + r.stderr).strip()
            if not texto:
                texto = {"encender": "Encendido.", "apagar": "Apagado.", "abrir-app": "Abierta en el celular."}.get(
                    orden, "") if r.returncode == 0 else "No salió bien."
            GLib.idle_add(self._decir, texto)
            GLib.idle_add(self._repasar)
        threading.Thread(target=trabajo, daemon=True).start()


def main(repo: str) -> int:
    if not os.environ.get("WAYLAND_DISPLAY") and not os.environ.get("DISPLAY"):
        print("El panel necesita una sesión gráfica.", file=sys.stderr)
        return 1
    GLib.set_prgname("celiuz-cast")   # la clase de la ventana en Wayland
    Panel(repo).show_all()
    Gtk.main()
    return 0
