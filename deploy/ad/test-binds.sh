#!/usr/bin/env bash
# (Samba does not implement ldapwhoami's extended operation, so we bind and search for the user's own entry.)
# Simple bind over LDAPS (samba-ad:636, lab CA) for every user in users.csv with its generated password.
# Prints "<ok>/<total> binds OK"; exits non-zero when any bind fails. Runs the LDAP client inside the
# samba-ad container (ldap-utils pinned with the image) so nothing is needed on the host.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-deploy/local/.env}"; case "$ENV_FILE" in /*) ;; *) ENV_FILE="$ROOT/$ENV_FILE" ;; esac
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT/deploy/local/docker-compose.yml")
PWFILE="$DIR/.secrets/passwords.env"
[ -f "$PWFILE" ] || { echo "no $PWFILE: run make ad-seed first" >&2; exit 1; }

ok=0; total=0
while IFS=';' read -r -u 3 user _; do
  case "$user" in ''|\#*) continue ;; esac
  total=$((total + 1))
  key="${user//./_}"; key="${key^^}"
  pw="$(sed -n "s/^${key}=//p" "$PWFILE" | head -n1)"
  # The password goes in on stdin and is read by ldapsearch -y /dev/stdin (no -w, no env var, no argv).
  if [ -n "$pw" ] && printf '%s' "$pw" | "${COMPOSE[@]}" exec -T -e LDAPTLS_CACERT=/var/lib/samba/tls/ca.pem samba-ad \
       bash -c "ldapsearch -LLL -x -H ldaps://samba-ad:636 -D '$user@yawar.lab' -y /dev/stdin -b 'OU=Personal,OU=Yawar,DC=yawar,DC=lab' '(sAMAccountName=$user)' dn | grep -q '^dn: '" >/dev/null 2>&1; then
    ok=$((ok + 1)); echo "  ok   $user"
  else
    echo "  FAIL $user" >&2
  fi
done 3< "$DIR/users.csv"
echo "$ok/$total binds OK"
[ "$ok" = "$total" ]
