#!/usr/bin/env bash
# tests/unidad/instalar.sh — ./instalar.sh en un $HOME y un /etc de mentira.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/../lib/comun.sh"
preparar_entorno
export CELIUZ_CAST_RAIZ="$TMP/raiz"
mkdir -p "$TMP/sys"; export CELIUZ_CAST_V4L2="$TMP/sys"     # sin cámara

titulo "1. Instalar lo del usuario"
bash "$REPO/instalar.sh" > "$TMP/salida1" 2>&1
afirmar_igual "$REPO/pc/celiuz-cast" "$(readlink -f "$HOME/.local/bin/celiuz-cast")" "el comando, enlazado al repo"
afirmar "el servicio, COPIADO (disable borra una unidad enlazada)" \
    sh -c "[ -f '$XDG_CONFIG_HOME/systemd/user/celiuz-cast.service' ] && [ ! -L '$XDG_CONFIG_HOME/systemd/user/celiuz-cast.service' ]"
afirmar "no se enciende solo al instalar" sh -c "! grep -qE '^(enable|start)' '$TMP/systemctl.llamadas'"
afirmar "el .desktop apunta al repo donde esté" grep -qx "Exec=$REPO/pc/celiuz-cast panel" "$XDG_DATA_HOME/applications/celiuz-cast.desktop"
afirmar "el icono" test -f "$XDG_DATA_HOME/icons/hicolor/scalable/apps/celiuz-cast.svg"
afirmar "avisa de que falta la cámara al arrancar" grep -q "no se crea sola al arrancar" "$TMP/salida1"

titulo "2. Lo del sistema"
bash "$REPO/instalar.sh" --sistema > "$TMP/salida2" 2>&1
afirmar "modprobe.d: igual que el del repo" cmp -s "$REPO/sistema/modprobe-celiuz-cast.conf" "$TMP/raiz/etc/modprobe.d/celiuz-cast.conf"
afirmar "modules-load.d: igual que el del repo" cmp -s "$REPO/sistema/modules-load-celiuz-cast.conf" "$TMP/raiz/etc/modules-load.d/celiuz-cast.conf"
afirmar "la cámara se llama Celular y es la 10" grep -q 'video_nr=10,11 card_label=Celular,OBS' "$REPO/sistema/modprobe-celiuz-cast.conf"

titulo "3. Revisar no toca nada, y una segunda pasada es igual"
mkdir -p "$TMP/sys/video10"; echo Celular > "$TMP/sys/video10/name"
antes="$(find "$HOME" -type f -o -type l | sort | xargs -I{} sh -c 'echo {}; cat {} 2>/dev/null | md5sum' )"
bash "$REPO/instalar.sh" --revisar > "$TMP/salida3" 2>&1
despues="$(find "$HOME" -type f -o -type l | sort | xargs -I{} sh -c 'echo {}; cat {} 2>/dev/null | md5sum' )"
afirmar_igual "$antes" "$despues" "--revisar no cambia ni un fichero"
afirmar "con todo puesto, la cámara sale bien" grep -q "se crea sola al arrancar" "$TMP/salida3"
bash "$REPO/instalar.sh" > /dev/null 2>&1
afirmar_igual "1" "$(grep -c . "$XDG_DATA_HOME/applications/celiuz-cast.desktop" | awk '{print ($1==8)}')" \
    "dos pasadas dejan el mismo .desktop"

titulo "4. Quitar"
bash "$REPO/instalar.sh" --quitar > /dev/null 2>&1
afirmar "quita el comando, el servicio y el lanzador" sh -c "! ls '$HOME/.local/bin/celiuz-cast' '$XDG_CONFIG_HOME/systemd/user/celiuz-cast.service' '$XDG_DATA_HOME/applications/celiuz-cast.desktop' 2>/dev/null | grep -q ."
afirmar "y el repo sigue ahí" test -f "$REPO/pc/celiuz-cast"
bash "$REPO/instalar.sh" --sistema-quitar > /dev/null 2>&1
afirmar "--sistema-quitar quita lo de /etc" sh -c "! find '$TMP/raiz' -type f | grep -q ."
resumen
