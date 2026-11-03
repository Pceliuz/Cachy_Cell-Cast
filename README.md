# Celiuz Cast

Comparte una app del celular (o la pantalla entera) **con su sonido** a la PC,
y la PC la ofrece a OBS como una cámara y un micrófono que se llaman
**«Celular»**. Pensado para hacer streams jugando desde el celular.

```
 Celular (app Celiuz Cast)                 PC (celiuz-cast)                  OBS
 ─────────────────────────                 ────────────────                  ───
 captura la app o la pantalla ──┐
 la codifica en H.264           ├─ cable USB (adb) ─▶ la decodifica ─▶ cámara «Celular»
 captura el sonido de la app ───┘   o wifi (adb)      y el sonido   ─▶ micrófono «Celular»
```

## Qué se comparte según tu Android

La app lo detecta sola y te lo dice al abrirla.

| Android | Imagen | Sonido |
|---|---|---|
| **14 o más** | **Solo la app que elijas.** Tus notificaciones no salen | Solo el de esa app |
| **10 a 13** | La pantalla entera. La app ofrece **«No molestar»** para que no salgan tus mensajes | Solo el de la app que elijas |
| 9 o menos | No funciona: Android no deja capturar el sonido | — |

Algunas apps prohíben que se capture su sonido (lo deciden sus
desarrolladores): entonces llega silencio y no hay forma de evitarlo.

## Instalarlo en la PC

En CachyOS / Arch:

```sh
git clone https://github.com/Pceliuz/Cachy_Cell-Cast.git ~/Proyectos/celiuz-cast
cd ~/Proyectos/celiuz-cast
./instalar.sh              # dice qué paquetes faltan, y pone el comando y el panel
./instalar.sh --sistema    # la cámara virtual, también tras reiniciar (pide la contraseña)
```

Se puede clonar en cualquier carpeta. `./instalar.sh` se puede pasar las
veces que haga falta; `./instalar.sh --revisar` solo dice qué falta.

Si `--sistema` dice que la cámara está en uso, cierra OBS y repítelo (o
reinicia).

## Instalar la app en el celular

1. En el celular: **Ajustes → Información del teléfono → toca 7 veces «Número
   de compilación»**. Luego, en **Opciones de desarrollador**, activa
   **«Depuración USB»**.
2. Enchúfalo a la PC por cable y acepta el aviso «¿Permitir depuración USB?»
   (marca «Permitir siempre desde este equipo»).
3. Abre **Celiuz Cast** en la PC y pulsa **«Instalar en el celular»** (o
   `celiuz-cast instalar-app`).

## Usarlo

1. En la PC, abre Celiuz Cast y **enciende el interruptor**. La cámara
   «Celular» enseña una imagen lisa mientras espera.
2. En el celular, abre Celiuz Cast, elige la app y la calidad, y pulsa
   **Transmitir**. Android pregunta qué compartir; en Android 14, elige
   «Una sola app» y la app.
3. En OBS:
   - **Imagen:** Fuente → «Dispositivo de captura de vídeo (V4L2)» →
     dispositivo «Celular», y en sus propiedades **Velocidad de fotogramas:
     60**. Si se queda en 30, se ve a tirones.
   - **Sonido:** Fuente → «Captura de entrada de audio (PulseAudio)» →
     «Celular». O en Ajustes → Audio, «Mic/Aux» = «Celular». La cámara no
     lleva sonido: va siempre aparte.
   - **Ajustes → Vídeo:** FPS **60**. Con 30, OBS tira imágenes del juego
     a saltos y también se ve a tirones. La resolución base, la del juego
     (1920x1080) y no la de tu pantalla.

   OBS no reproduce por los altavoces lo que graba: para oír el celular
   mientras pruebas, en Propiedades avanzadas de audio pon «Celular» en
   «Monitorizar y emitir».

La cámara es siempre de 1920x1080: si giras el celular, la imagen entra con
bandas y OBS no tiene que reconfigurar nada. Si el cable parpadea, se
reconecta sola; mientras tanto se ve la última imagen y luego la de espera.

Si la conexión no da para más, el celular baja la calidad un rato (y la sube
después) en vez de acumular retraso.

### Por wifi

Funciona igual con la **depuración inalámbrica** de Android 11+. Empareja una
vez (`adb pair IP:PUERTO`, con el código que enseña el celular) y conéctate
(`adb connect IP:PUERTO`); el receptor lo usa como si fuera el cable. El
cable da menos retraso y carga el celular.

## Ajustes

`~/.config/celiuz-cast/ajustes.conf` (no hace falta crearlo):

```ini
salida = 1920x1080     # tamaño de la cámara «Celular» (1280x720 gasta menos)
celular = SERIE        # con varios celulares enchufados, cuál usar (adb devices)
grafica = no           # decodificar por software aunque haya VA-API
```

## Un icono en la barra (waybar)

`celiuz-cast barra` imprime una línea JSON para un módulo `custom` de waybar:
vacío si está apagado, y un icono con el estado en el tooltip si está
encendido.

```jsonc
"custom/celiuz-cast": {
    "exec": "celiuz-cast barra",
    "return-type": "json",
    "interval": 2,
    "on-click": "celiuz-cast panel"
}
```

## Si algo falla

- **La imagen sale rayada en OBS:** OBS se quedó con un formato viejo de la
  cámara. Cierra OBS del todo y ábrelo.
- **Se congela de vez en cuando:** casi siempre es el cable o el puerto USB.
  `journalctl -k | grep -i usb` enseña «USB disconnect» si es eso.
- **«Esperando…» y nunca transmite:** mira que la app esté en «Transmitir» y
  que el panel no diga que falta algo.
- **No hay sonido:** la app que compartes puede prohibir su captura. Prueba
  con YouTube o música para descartarlo.
- `celiuz-cast revisar` dice qué falta en el equipo.

## Para quien lo toque

- `PROTOCOLO.md` — el contrato entre el celular y la PC.
- `CLAUDE.md` — cómo se trabaja aquí y las trampas ya medidas.
- `./tests/run.sh` — las pruebas (no necesitan ni celular ni OBS).
- `app/compilar.sh` — compila el APK (necesita el SDK de Android).
