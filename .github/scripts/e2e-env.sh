#!/usr/bin/env bash
# Creates deploy/local/.env for the E2E run in CI from .env.example: every placeholder secret (change-me-*, CHANGE_ME)
# gets a random value, which is registered as a masked value of the GitHub log. Values are never printed.
# The config repository is the folder deploy/local/e2e-config of this repo (yawar-uce/yawar-config is private).
# Usage: .github/scripts/e2e-env.sh [env-file-to-write]   (default deploy/local/.env)
set -euo pipefail

src=deploy/local/.env.example
dst=${1:-deploy/local/.env}

umask 077
: > "$dst"
while IFS= read -r line || [ -n "$line" ]; do
  if [[ $line =~ ^([A-Z0-9_]+)=(.*)$ ]]; then
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    if [[ $value == change-me* || $value == CHANGE_ME* ]]; then
      secret=$(openssl rand -hex 16)
      if [ -n "${GITHUB_ACTIONS:-}" ]; then
        echo "::add-mask::$secret"
      fi
      line="$key=$secret"
    elif [[ $key == YAWAR_CONFIG_DIR ]]; then
      line="$key=./e2e-config"
    fi
  fi
  printf '%s\n' "$line" >> "$dst"
done < "$src"

if grep -qiE '=(change-me|CHANGE_ME)' "$dst"; then
  echo "Placeholders left in $dst" >&2
  exit 1
fi
echo "Wrote $dst ($(grep -cE '^[A-Z0-9_]+=' "$dst") variables, secrets random)"
