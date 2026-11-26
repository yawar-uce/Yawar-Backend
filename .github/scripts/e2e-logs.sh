#!/usr/bin/env bash
# Prints the container status and the last log lines of the E2E stack with every secret of deploy/local/.env
# (keys ending in PASSWORD, SECRET or TOKEN) replaced by ***. Used on failure only.
# Usage: .github/scripts/e2e-logs.sh [lines-per-service]
set -uo pipefail

lines=${1:-200}
env_file=deploy/local/.env
compose=(docker compose --env-file "$env_file" -f deploy/local/docker-compose.yml --profile '*')

redact() {
  awk -v envfile="$env_file" '
    BEGIN {
      while ((getline l < envfile) > 0) {
        if (match(l, /^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN)=/)) {
          v = substr(l, RLENGTH + 1)
          if (length(v) >= 6) secrets[n++] = v
        }
      }
    }
    {
      for (i = 0; i < n; i++) {
        while ((p = index($0, secrets[i])) > 0) $0 = substr($0, 1, p - 1) "***" substr($0, p + length(secrets[i]))
      }
      print
    }'
}

{
  "${compose[@]}" ps --all --format 'table {{.Service}}\t{{.Status}}'
  "${compose[@]}" logs --no-color --tail="$lines"
} 2>&1 | redact
