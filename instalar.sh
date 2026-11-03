#!/usr/bin/env bash
# instalar.sh — deja Celiuz Cast listo en este equipo. Se puede pasar las veces
# que haga falta: solo hace lo que falte.
#
#   ./instalar.sh              lo de tu usuario: el comando, el servicio, el panel
#   ./instalar.sh --sistema    la cámara virtual al arrancar (pide la contraseña)
#   ./instalar.sh --revisar    solo dice qué falta, sin tocar nada
#   ./instalar.sh --quitar     quita lo de tu usuario
#   ./instalar.sh --sistema-quitar   quita lo del sistema
#
# Nada de lo que se instala apunta a una carpeta fija: el repo se puede clonar
# donde sea. El comando es un enlace a pc/celiuz-cast, que saca la raíz de su
# propia ruta.

set -u
REPO="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
RAIZ="${CELIUZ_CAST_RAIZ:-}"          # las pruebas: un /etc de mentira
BIN="$HOME/.local/bin"
CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}"
DATOS="${XDG_DATA_HOME:-$HOME/.local/share}"
UNIDAD="$CONFIG/systemd/user/celiuz-cast.service"
ESCRITORIO="$DATOS/applications/celiuz-cast.desktop"
ICONO="$DATOS/icons/hicolor/scalable/apps/celiuz-cast.svg"
# El anuncio para el catálogo de los dotfiles de Celiuz: un enlace a este repo,
# para que sepan que está instalada y dónde, se clone donde se clone.
ANUNCIO="$DATOS/celiuz/apps.d/cell-cast"
MODPROBE="$RAIZ/etc/modprobe.d/celiuz-cast.conf"
MODULOS="$RAIZ/etc/modules-load.d/celiuz-cast.conf"

MODO="${1:-instalar}"
AVISOS=0

if [ -t 1 ]; then V=$'\e[32m'; A=$'\e[33m'; R=$'\e[31m'; G=$'\e[2m'; N=$'\e[0m'; else V= A= R= G= N=; fi
hecho()  { echo "  ${V}bien${N}   $*"; }
aviso()  { echo "  ${A}falta${N}  $*"; AVISOS=$((AVISOS + 1)); }
gris()   { echo "  ${G}$*${N}"; }
titulo() { echo; echo "$*"; }

# --- 1. Lo que tiene que haber instalado --------------------------------------
dependencias() {
    titulo "Programas"
    local faltan=()
    command -v adb   >/dev/null || faltan+=(android-tools)
    command -v pactl >/dev/null || faltan+=(pipewire-pulse)
    python3 -c 'import gi; gi.require_version("Gst","1.0"); gi.require_version("Gtk","3.0")' 2>/dev/null \
        || faltan+=(python-gobject gtk3 gstreamer)
    local g
    for g in appsrc:gst-plugins-base h264parse:gst-plugins-bad avdec_h264:gst-libav videoscale:gst-plugins-base; do
        gst-inspect-1.0 "${g%%:*}" >/dev/null 2>&1 || faltan+=("${g#*:}")
    done
    if [ ${#faltan[@]} -eq 0 ]; then
        hecho "adb, PipeWire, GStreamer y GTK"
    else
        aviso "faltan paquetes: sudo pacman -S --needed $(printf '%s\n' "${faltan[@]}" | sort -u | tr '\n' ' ')"
    fi
    if gst-inspect-1.0 vah264dec >/dev/null 2>&1; then
        hecho "decodificar con la gráfica (VA-API)"
    else
        gris "-      sin VA-API: se decodifica por software (opcional: gst-plugin-va)"
    fi
    if modinfo v4l2loopback >/dev/null 2>&1; then
        hecho "v4l2loopback (la cámara virtual) está en el kernel"
    else
        aviso "falta v4l2loopback: sudo pacman -S v4l2loopback-dkms (y reinicia)"
    fi
}

# --- 2. Lo de tu usuario --------------------------------------------------------
usuario() {
    titulo "Tu usuario"
    if [ "$(readlink -f "$BIN/celiuz-cast" 2>/dev/null)" = "$REPO/pc/celiuz-cast" ]; then
        hecho "el comando celiuz-cast"
    elif [ "$MODO" = "--revisar" ]; then
        aviso "el comando celiuz-cast (~/.local/bin)"
    else
        mkdir -p "$BIN" && ln -sfn "$REPO/pc/celiuz-cast" "$BIN/celiuz-cast" && hecho "el comando celiuz-cast (~/.local/bin)"
    fi

    # El servicio se COPIA, no se enlaza: `systemctl --user disable` borra una
    # unidad que sea un enlace a fuera de las carpetas de systemd.
    if cmp -s "$REPO/pc/datos/celiuz-cast.service" "$UNIDAD"; then
        hecho "el servicio celiuz-cast (apagado hasta que lo enciendas)"
    elif [ "$MODO" = "--revisar" ]; then
        aviso "el servicio celiuz-cast"
    else
        mkdir -p "$(dirname "$UNIDAD")" && cp "$REPO/pc/datos/celiuz-cast.service" "$UNIDAD"
        systemctl --user daemon-reload 2>/dev/null
        hecho "el servicio celiuz-cast (apagado hasta que lo enciendas)"
    fi

    local escritorio
    escritorio="[Desktop Entry]
Type=Application
Name=Celiuz Cast
Comment=El celular como cámara y micrófono para OBS
Exec=$REPO/pc/celiuz-cast panel
Icon=celiuz-cast
Categories=AudioVideo;Video;
StartupWMClass=celiuz-cast"
    if [ "$(cat "$ESCRITORIO" 2>/dev/null)" = "$escritorio" ] && cmp -s "$REPO/pc/datos/celiuz-cast.svg" "$ICONO"; then
        hecho "Celiuz Cast en el lanzador de apps"
    elif [ "$MODO" = "--revisar" ]; then
        aviso "Celiuz Cast en el lanzador de apps"
    else
        mkdir -p "$(dirname "$ESCRITORIO")" "$(dirname "$ICONO")"
        printf '%s\n' "$escritorio" > "$ESCRITORIO"
        cp "$REPO/pc/datos/celiuz-cast.svg" "$ICONO"
        hecho "Celiuz Cast en el lanzador de apps"
    fi

    if [ "$(readlink -f "$ANUNCIO" 2>/dev/null)" = "$REPO" ]; then
        hecho "anunciada al catálogo de los dotfiles"
    elif [ "$MODO" = "--revisar" ]; then
        aviso "sin anunciar al catálogo de los dotfiles"
    else
        mkdir -p "$(dirname "$ANUNCIO")" && ln -sfn "$REPO" "$ANUNCIO" && hecho "anunciada al catálogo de los dotfiles"
    fi

    if [ -f "$REPO/app/celiuz-cast.apk" ]; then
        hecho "la app del celular (app/celiuz-cast.apk): se instala desde el panel"
    else
        aviso "no está app/celiuz-cast.apk: compílala con app/compilar.sh"
    fi
}

# --- 3. La cámara virtual al arrancar (root) ---------------------------------
sistema_estado() {
    titulo "La cámara virtual"
    if cmp -s "$REPO/sistema/modprobe-celiuz-cast.conf" "$MODPROBE" &&
       cmp -s "$REPO/sistema/modules-load-celiuz-cast.conf" "$MODULOS"; then
        hecho "se crea sola al arrancar"
    else
        aviso "no se crea sola al arrancar: ./instalar.sh --sistema"
    fi
    if "$REPO/pc/celiuz-cast" camara-existe 2>/dev/null; then
        hecho "la cámara «Celular» existe ahora"
    else
        aviso "la cámara «Celular» no existe ahora: ./instalar.sh --sistema (o reinicia)"
    fi
}

sistema_instalar() {
    titulo "La cámara virtual (pide tu contraseña)"
    local sudo=sudo
    [ -n "$RAIZ" ] && sudo=""          # las pruebas no piden nada
    $sudo install -Dm644 "$REPO/sistema/modprobe-celiuz-cast.conf" "$MODPROBE" &&
    $sudo install -Dm644 "$REPO/sistema/modules-load-celiuz-cast.conf" "$MODULOS" ||
        { aviso "no se pudieron copiar los ficheros a /etc"; return; }
    hecho "se creará sola al arrancar ($MODPROBE)"
    [ -n "$RAIZ" ] && return

    # Ahora mismo: si el módulo está cargado con otras opciones y nadie lo usa, se recarga.
    if "$REPO/pc/celiuz-cast" camara-existe; then
        hecho "la cámara «Celular» ya existe"
    elif [ -d /sys/module/v4l2loopback ] && [ "$(cat /sys/module/v4l2loopback/refcnt 2>/dev/null)" != "0" ]; then
        aviso "la cámara virtual está en uso (¿OBS abierto?): cierra lo que la use y repite, o reinicia"
    else
        sudo modprobe -r v4l2loopback 2>/dev/null
        if sudo modprobe v4l2loopback && sleep 1 && "$REPO/pc/celiuz-cast" camara-existe; then
            hecho "la cámara «Celular» ya existe"
        else
            aviso "no se pudo crear la cámara ahora: reinicia el equipo"
        fi
    fi
}

sistema_quitar() {
    titulo "Quitar la cámara virtual del arranque"
    local sudo=sudo
    [ -n "$RAIZ" ] && sudo=""
    $sudo rm -f "$MODPROBE" "$MODULOS" && hecho "quitada (sigue hasta reiniciar)"
}

quitar() {
    titulo "Quitar Celiuz Cast de tu usuario"
    systemctl --user stop celiuz-cast.service 2>/dev/null
    rm -f "$UNIDAD" "$ESCRITORIO" "$ICONO"
    [ "$(readlink -f "$BIN/celiuz-cast" 2>/dev/null)" = "$REPO/pc/celiuz-cast" ] && rm -f "$BIN/celiuz-cast"
    [ "$(readlink -f "$ANUNCIO" 2>/dev/null)" = "$REPO" ] && rm -f "$ANUNCIO"
    systemctl --user daemon-reload 2>/dev/null
    hecho "quitado (la carpeta del repo se queda)"
}

echo "Celiuz Cast — $REPO"
case "$MODO" in
    instalar|--revisar)
        dependencias; usuario; sistema_estado
        echo
        if [ "$AVISOS" -eq 0 ]; then
            echo "Todo listo. Abre «Celiuz Cast» desde el lanzador (o: celiuz-cast panel)."
        else
            [ "$AVISOS" -eq 1 ] && echo "Falta 1 cosa (arriba, en amarillo)." || echo "Faltan $AVISOS cosas (arriba, en amarillo)."
        fi
        [ "$AVISOS" -eq 0 ] ;;
    --sistema) sistema_instalar ;;
    --sistema-quitar) sistema_quitar ;;
    --quitar) quitar ;;
    *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
