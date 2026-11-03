"""El receptor: busca el celular, recibe lo que manda y lo convierte en la
cámara «Celular» y el micrófono «Celular».

Nunca se rinde: si el cable parpadea (pasó en la primera prueba: error -71
del USB y scrcpy muerto con la imagen congelada en OBS), vuelve a abrir el
túnel y sigue. Mientras tanto la cámara enseña la última imagen unos segundos
y luego una imagen lisa del color del escritorio, para que en el stream se
note «pausa» y no un congelado.
"""

import socket
import threading
import time

from . import adb, protocolo
from .estado import Estado

ULTIMA_IMAGEN_S = 3.0      # tras un corte, cuánto se sigue enseñando la última imagen
SIN_DATOS_S = 3.0          # sin recibir nada en este tiempo, la conexión está muerta
REPASO_APP_S = 10.0        # cada cuánto se vuelve a mirar si la app está instalada


class Receptor:
    def __init__(self, abrir_camara, microfono, estado: Estado, ajustes: dict, directo=None,
                 nuevo_decodificador=None):
        """
        abrir_camara: función sin argumentos que devuelve una Camara (o lanza OSError).
        directo: (host, puerto) para conectarse sin adb (las pruebas).
        """
        self.abrir_camara = abrir_camara
        self.camara = None
        self.microfono = microfono
        self.estado = estado
        self.ajustes = ajustes
        self.directo = directo
        self.nuevo_decodificador = nuevo_decodificador or self._decodificador_gst
        self.vivo = True
        self.cerrojo = threading.Lock()
        self.ultima = None
        self.momento_ultima = 0.0
        self.conectado = False
        self.desconectado_en = 0.0
        self.decodificador = None
        self.sock = None
        self.config_video = None
        self._apps_vistas = {}
        self._imagenes_seg = []

    # --- La cámara (siempre recibe algo) ----------------------------------

    def _decodificador_gst(self, ancho, alto, al_imagen):
        from .video import Decodificador
        return Decodificador(ancho, alto, al_imagen, preferir_grafica=self.ajustes.get("grafica", "si") != "no")

    def _al_imagen(self, imagen: bytes):
        with self.cerrojo:
            if self.camara:
                self.camara.escribir(imagen)
            self.ultima = imagen
            self.momento_ultima = time.monotonic()
            self._imagenes_seg.append(self.momento_ultima)

    def _vigia(self):
        """Mantiene la cámara con imagen y el estado al día."""
        lisa = None
        while self.vivo:
            ahora = time.monotonic()
            with self.cerrojo:
                if self.camara is None:
                    try:
                        self.camara = self.abrir_camara()
                        self.estado.poner(camara=True)
                    except OSError as e:
                        self.estado.poner(camara=False, error_camara=str(e))
                if self.camara is not None and ahora - self.momento_ultima > 0.5:
                    if lisa is None or len(lisa) != self.camara.tamano:
                        lisa = self.camara.lisa()
                    corto = not self.conectado and ahora - self.desconectado_en < ULTIMA_IMAGEN_S
                    self.camara.escribir(self.ultima if (corto and self.ultima) else lisa)
                self._imagenes_seg = [t for t in self._imagenes_seg if ahora - t < 1.0]
                fps = len(self._imagenes_seg)
            self.estado.poner(fps=fps if self.conectado else 0)
            self.estado.guardar()
            time.sleep(0.2)

    # --- El bucle principal -----------------------------------------------

    def correr(self):
        self.microfono.abrir()
        hilo = threading.Thread(target=self._vigia, name="vigia", daemon=True)
        hilo.start()
        try:
            while self.vivo:
                destino = self._destino()
                if destino is None:
                    time.sleep(1.5)
                    continue
                serie, host, puerto = destino
                try:
                    self._sesion(host, puerto)
                except (protocolo.Cortado, OSError) as e:
                    if self.conectado:
                        self.estado.poner(estado="reconectando", texto="Se cortó la conexión; reconectando…",
                                          detalle=str(e))
                    elif self.estado.datos.get("estado") != "esperando":
                        self.estado.poner(estado="esperando",
                                          texto="Abre Celiuz Cast en el celular y pulsa Transmitir.")
                finally:
                    self._fin_sesion()
                    if serie:
                        adb.quitar_tunel(serie, puerto)
                time.sleep(0.7)
        finally:
            self.vivo = False
            hilo.join(2)
            self._fin_sesion()
            self.microfono.cerrar()
            with self.cerrojo:
                if self.camara:
                    self.camara.cerrar()
            self.estado.poner(estado="apagado", texto="Apagado")
            self.estado.guardar()

    def detener(self):
        self.vivo = False

    def _destino(self):
        """(serie, host, puerto) del celular al que conectarse, o None (y el porqué en el estado)."""
        if self.directo:
            return None, self.directo[0], self.directo[1]
        if not adb.hay_adb():
            self.estado.poner(estado="sin_adb", texto="Falta adb: instala el paquete android-tools.")
            return None
        lista = adb.celulares()
        preferido = self.ajustes.get("celular")
        lista.sort(key=lambda c: c["serie"] != preferido)
        listos = [c for c in lista if c["estado"] == "device"]
        if not listos:
            if any(c["estado"] == "unauthorized" for c in lista):
                self.estado.poner(estado="sin_permiso",
                                  texto="Acepta la depuración USB en el celular (sale un aviso en su pantalla).")
            else:
                self.estado.poner(estado="sin_celular",
                                  texto="Conecta el celular por cable, con la depuración USB activada.")
            return None
        ahora = time.monotonic()
        elegido = None
        for c in listos:
            visto = self._apps_vistas.get(c["serie"])
            if visto is None or ahora - visto[1] > REPASO_APP_S:
                visto = (adb.tiene_app(c["serie"]), ahora)
                self._apps_vistas[c["serie"]] = visto
            if visto[0]:
                elegido = c
                break
        if elegido is None:
            self.estado.poner(estado="sin_app", serie=listos[0]["serie"],
                              texto="Falta la app en el celular: pulsa «Instalar en el celular» en el panel.")
            return None
        try:
            puerto = adb.tunel(elegido["serie"])
        except OSError as e:
            self.estado.poner(estado="sin_celular", texto=f"No se pudo abrir el túnel: {e}")
            return None
        self.estado.poner(serie=elegido["serie"], modelo_adb=elegido["modelo"])
        return elegido["serie"], "127.0.0.1", puerto

    # --- Una conexión -----------------------------------------------------

    def _sesion(self, host, puerto):
        s = socket.create_connection((host, puerto), timeout=SIN_DATOS_S)
        self.sock = s
        s.settimeout(SIN_DATOS_S)
        protocolo.leer_saludo(s)
        self.conectado = True
        self.estado.poner(estado="transmitiendo", texto="Transmitiendo", detalle=None)
        while self.vivo:
            try:
                tipo, banderas, pts, datos = protocolo.leer(s)
            except socket.timeout:
                raise protocolo.Cortado(f"{SIN_DATOS_S:.0f} s sin recibir nada del celular")
            if tipo == protocolo.VIDEO:
                self._video(banderas, datos)
            elif tipo == protocolo.AUDIO:
                self.microfono.escribir(datos)
            elif tipo == protocolo.VIDEO_CONFIG:
                self._config_video(protocolo.json_de(datos))
            elif tipo == protocolo.HOLA:
                hola = protocolo.json_de(datos)
                self.estado.poner(celular=hola)
            elif tipo == protocolo.AUDIO_CONFIG:
                self.estado.poner(sonido=protocolo.json_de(datos))
            elif tipo == protocolo.ESTADO:
                e = protocolo.json_de(datos)
                self.estado.poner(bitrate=e.get("bitrate"), visible=e.get("visible", True), aviso=e.get("aviso"))
            elif tipo == protocolo.FIN:
                self.estado.poner(estado="esperando", texto="El celular paró la transmisión.")
                self.conectado = False
                return

    def _config_video(self, cfg: dict):
        if self.decodificador:
            self.decodificador.cerrar()
            self.decodificador = None
        self.config_video = None
        with self.cerrojo:
            camara = self.camara
        if camara is None:
            return
        self.decodificador = self.nuevo_decodificador(camara.ancho, camara.alto, self._al_imagen)
        self.estado.poner(video={"ancho": cfg.get("ancho"), "alto": cfg.get("alto"),
                                 "salida": f"{camara.ancho}x{camara.alto}",
                                 "decodifica": getattr(self.decodificador, "tipo", "?")})

    def _video(self, banderas, datos):
        if banderas & protocolo.CONFIG:
            self.config_video = datos
            return
        if self.decodificador is None:
            return
        # La configuración delante de cada clave: así una clave sola basta para arrancar.
        if banderas & protocolo.CLAVE and self.config_video and not datos.startswith(self.config_video):
            datos = self.config_video + datos
        try:
            self.decodificador.empujar(datos)
        except RuntimeError as e:
            self.estado.poner(detalle=str(e))
            self.sock.sendall(protocolo.paquete(protocolo.PEDIR_CLAVE))

    def _fin_sesion(self):
        if self.conectado:
            self.desconectado_en = time.monotonic()
        self.conectado = False
        if self.decodificador:
            self.decodificador.cerrar()
            self.decodificador = None
        s = getattr(self, "sock", None)
        if s:
            try:
                s.close()
            except OSError:
                pass
            self.sock = None


def leer_ajustes(ruta: str) -> dict:
    """ajustes.conf: líneas `clave = valor`; # para comentarios."""
    ajustes = {}
    try:
        with open(ruta) as f:
            for linea in f:
                linea = linea.split("#", 1)[0].strip()
                if "=" in linea:
                    k, v = linea.split("=", 1)
                    ajustes[k.strip()] = v.strip()
    except OSError:
        pass
    return ajustes


def tamano_salida(ajustes: dict):
    texto = ajustes.get("salida", "1920x1080")
    try:
        w, h = (int(x) for x in texto.lower().split("x"))
        if w >= 160 and h >= 120 and w % 2 == 0 and h % 2 == 0:
            return w, h
    except ValueError:
        pass
    return 1920, 1080

