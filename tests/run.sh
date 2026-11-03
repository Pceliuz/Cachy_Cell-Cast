#!/usr/bin/env bash
# tests/run.sh — todas las pruebas, o las que lleven ese texto en el nombre.
#   ./tests/run.sh            todas (la de la app, solo si hay SDK de Android)
#   ./tests/run.sh receptor   solo las que se llamen así
set -u
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
filtro="${1:-}"
total=0; malas=()
for t in "$DIR"/unidad/*.sh "$DIR"/e2e/*.sh; do
    nombre="$(basename "$(dirname "$t")")/$(basename "$t" .sh)"
    [ -n "$filtro" ] && [[ "$nombre" != *"$filtro"* ]] && continue
    total=$((total + 1))
    echo "== $nombre"
    if bash "$t"; then :; else malas+=("$nombre"); fi
done
echo
if [ ${#malas[@]} -eq 0 ]; then echo "$total/$total pruebas bien"; else
    echo "$(( total - ${#malas[@]} ))/$total pruebas bien. Fallan: ${malas[*]}"; exit 1; fi
