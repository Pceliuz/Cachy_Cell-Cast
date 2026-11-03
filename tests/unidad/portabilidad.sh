#!/usr/bin/env bash
# tests/unidad/portabilidad.sh — nada de rutas de UNA máquina en el código: el
# repo se clona donde sea y en el equipo de otra persona.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/../lib/comun.sh"

titulo "Rutas cableadas"
hallado="$(cd "$REPO" && git ls-files 2>/dev/null | grep -vE '\.(md|apk|jar|png)$' | grep -v '^tests/unidad/portabilidad.sh$' |
    xargs grep -nE '/home/[a-z]|Proyectos/celiuz-cast|~/Proyectos' 2>/dev/null)"
[ -z "$(cd "$REPO" && git ls-files 2>/dev/null)" ] && hallado="$(cd "$REPO" && grep -rnE '/home/[a-z]|Proyectos/celiuz-cast|~/Proyectos' \
    --exclude-dir=build --exclude-dir=.gradle --exclude='*.md' --exclude='*.apk' --exclude=portabilidad.sh --exclude=local.properties . 2>/dev/null)"
afirmar_igual "" "$hallado" "ningún fichero nombra una carpeta de usuario"
afirmar "local.properties (la ruta del SDK de cada equipo) no se versiona" grep -qx 'app/local.properties' "$REPO/.gitignore"
afirmar "la firma del APK vive fuera del repo" sh -c "! ls '$REPO'/app/*.p12 '$REPO'/app/*.jks 2>/dev/null | grep -q ."
afirmar "el icono empieza por <svg (gdk-pixbuf no acepta otro principio)" sh -c "head -c 4 '$REPO/pc/datos/celiuz-cast.svg' | grep -q '<svg'"
resumen
