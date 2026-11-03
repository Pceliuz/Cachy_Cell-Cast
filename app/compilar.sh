#!/usr/bin/env bash
# app/compilar.sh — compila la app del celular y deja app/celiuz-cast.apk.
#
# Necesita el SDK de Android (ANDROID_HOME, o ~/Android/Sdk) y Java 17+.
# La firma vive FUERA del repo, en ~/.config/celiuz-cast/: se crea la primera
# vez. Ojo: un APK firmado con otra clave no se instala encima del anterior
# (Android pide desinstalar primero), así que compila siempre en el mismo
# equipo o copia esa carpeta al otro.

set -euo pipefail
APP="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
FIRMA="${XDG_CONFIG_HOME:-$HOME/.config}/celiuz-cast"

[ -d "$SDK/platforms" ] || { echo "No encuentro el SDK de Android en $SDK (pon ANDROID_HOME)." >&2; exit 1; }
echo "sdk.dir=$SDK" > "$APP/local.properties"

if [ ! -f "$FIRMA/firma.properties" ]; then
    echo "Creando la clave de firma en $FIRMA (una sola vez)…"
    mkdir -p "$FIRMA"; chmod 700 "$FIRMA"
    clave="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
    keytool -genkeypair -keystore "$FIRMA/firma.p12" -storetype PKCS12 -alias celiuz-cast \
        -keyalg RSA -keysize 3072 -validity 10000 -storepass "$clave" -keypass "$clave" \
        -dname "CN=Celiuz Cast" >/dev/null
    chmod 600 "$FIRMA/firma.p12"
    umask 077
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=celiuz-cast\nkeyPassword=%s\n' \
        "$FIRMA/firma.p12" "$clave" "$clave" > "$FIRMA/firma.properties"
fi

cd "$APP"
./gradlew --no-daemon -q testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk celiuz-cast.apk
echo "Listo: $APP/celiuz-cast.apk ($(du -h celiuz-cast.apk | cut -f1))"
