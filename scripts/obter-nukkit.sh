#!/usr/bin/env bash
# Baixa o core validado do repositorio Nukkit-Gold-Edition para lib/nukkit-gold.jar (usado so para compilar).
set -euo pipefail
raiz="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ref="${NUKKIT_REF:-main}"
base="https://raw.githubusercontent.com/gustavoz65/Nukkit-Gold-Edition/$ref/builds"
temp="$(mktemp -d)"; trap 'rm -rf -- "$temp"' EXIT
curl -fsSL "$base/nukkit-gold-0.15.10.jar" -o "$temp/nukkit-gold.jar"
esperado="$(curl -fsSL "$base/SHA256SUMS" | awk '$2 == "nukkit-gold-0.15.10.jar" { print $1 }')"
obtido="$(sha256sum "$temp/nukkit-gold.jar" | awk '{ print $1 }')"
[[ -n "$esperado" && "$esperado" == "$obtido" ]] || { printf 'Erro: SHA-256 do core nao confere.\n' >&2; exit 1; }
mkdir -p "$raiz/lib" && mv "$temp/nukkit-gold.jar" "$raiz/lib/nukkit-gold.jar"
printf 'Core salvo em lib/nukkit-gold.jar (%s)\n' "$obtido"
