#!/usr/bin/env bash
# tests/unidad/app-logica.sh — la lógica de la app del celular (detector de
# versión, tamaños, cola con atascos, protocolo), con JUnit en la PC.
# Necesita el SDK de Android; sin él, se salta (no es un fallo).
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/../lib/comun.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK/platforms" ]; then echo "  (sin SDK de Android: se salta)"; exit 0; fi
[ -f "$REPO/app/local.properties" ] || echo "sdk.dir=$SDK" > "$REPO/app/local.properties"
titulo "JUnit de la app"
salida="$(cd "$REPO/app" && ./gradlew --no-daemon -q testDebugUnitTest 2>&1)"; codigo=$?
afirmar_igual "0" "$codigo" "compila y pasan las pruebas de la app"
[ "$codigo" -ne 0 ] && echo "$salida" | grep -v '^w: ' | tail -20
total="$(cat "$REPO"/app/app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o 'tests="[0-9]*"' | grep -o "[0-9]*" | awk "{s+=\$1} END {print s}")"
afirmar "son al menos 16" test "${total:-0}" -ge 16
resumen
