# tests/lib/comun.sh — lo que comparten las pruebas.
#
# Cada prueba se monta un $HOME de mentira (preparar_entorno) y sustituye lo
# peligroso por binarios falsos (binario_falso): nada toca tu sesión, tu
# PipeWire, tu systemd ni tu celular.

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIEN=0; MAL=0

afirmar() {          # afirmar "descripción" comando...
    local d="$1"; shift
    if "$@" >/dev/null 2>&1; then BIEN=$((BIEN + 1)); echo "  bien  $d"
    else MAL=$((MAL + 1)); echo "  MAL   $d"; fi
}
afirmar_igual() {    # afirmar_igual esperado obtenido "descripción"
    if [ "$1" = "$2" ]; then BIEN=$((BIEN + 1)); echo "  bien  $3"
    else MAL=$((MAL + 1)); echo "  MAL   $3"; echo "        esperaba: $1"; echo "        obtuve:   $2"; fi
}
titulo() { echo " $*"; }

preparar_entorno() {
    TMP="$(mktemp -d)"
    trap 'limpiar' EXIT
    export HOME="$TMP/casa" XDG_CONFIG_HOME="$TMP/casa/.config" XDG_DATA_HOME="$TMP/casa/.local/share"
    export XDG_STATE_HOME="$TMP/casa/.local/state" XDG_CACHE_HOME="$TMP/casa/.cache"
    export XDG_RUNTIME_DIR="$TMP/run"
    mkdir -p "$HOME" "$XDG_RUNTIME_DIR" "$TMP/bin"
    chmod 700 "$XDG_RUNTIME_DIR"
    unset WAYLAND_DISPLAY DISPLAY DBUS_SESSION_BUS_ADDRESS
    export PATH="$TMP/bin:$PATH"
    # Lo que tocaría el sistema de verdad, siempre falso.
    binario_falso systemctl 0
    binario_falso pactl 0
    binario_falso sudo 0
    binario_falso modprobe 0
}

limpiar() {
    local p
    for p in ${PIDS_LIMPIAR:-}; do kill "$p" 2>/dev/null; done
    wait 2>/dev/null
    rm -rf "$TMP"
}

# binario_falso <nombre> <código> [cuerpo de shell]: apunta sus argumentos en
# $TMP/<nombre>.llamadas y hace lo que diga el cuerpo.
binario_falso() {
    local nombre="$1" codigo="$2" cuerpo="${3:-}"
    cat > "$TMP/bin/$nombre" <<F
#!/usr/bin/env bash
echo "\$*" >> "$TMP/$nombre.llamadas"
$cuerpo
exit $codigo
F
    chmod +x "$TMP/bin/$nombre"
}

# esperar_a <segundos> <función>: repite la función hasta que salga bien.
esperar_a() {
    local hasta=$(( $(date +%s%N) + $1 * 1000000000 )); shift
    while [ "$(date +%s%N)" -lt "$hasta" ]; do "$@" && return 0; sleep 0.1; done
    return 1
}

resumen() {
    echo "  ($BIEN bien, $MAL mal)"
    [ "$MAL" -eq 0 ]
}
