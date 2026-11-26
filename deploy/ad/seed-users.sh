#!/usr/bin/env bash
# Idempotent seed of yawar.lab (YAW-30): OU=Yawar > OU=Personal / OU=Grupos, 5 groups, 10 staff users (users.csv).
# Usage: make ad-seed [ARGS="--reset-passwords --must-change"]
#   --reset-passwords  generate and set a new password for every user (default: existing users keep theirs)
#   --must-change      users must change the password at next login (default off: LDAP bind tests and the
#                      Keycloak federation demo need usable passwords)
# Generated passwords go to deploy/ad/.secrets/passwords.env (git-ignored, mode 600). Nothing is printed.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-deploy/local/.env}"; case "$ENV_FILE" in /*) ;; *) ENV_FILE="$ROOT/$ENV_FILE" ;; esac
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT/deploy/local/docker-compose.yml")
SECRETS="$DIR/.secrets"; PWFILE="$SECRETS/passwords.env"
BASE_DN="DC=yawar,DC=lab"; OU_USERS="OU=Personal,OU=Yawar"; OU_GROUPS="OU=Grupos,OU=Yawar"
RESET=0; MUST=0
for a in "$@"; do
  case "$a" in
    --reset-passwords) RESET=1 ;;
    --must-change) MUST=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

CHANGES=0
ad() { "${COMPOSE[@]}" exec -T samba-ad "$@"; }
LDAPI=/var/lib/samba/private/sam.ldb
sam() { ad samba-tool "$@"; }
changed() { CHANGES=$((CHANGES + 1)); echo "  + $*"; }

umask 077
mkdir -p "$SECRETS"; touch "$PWFILE"; chmod 600 "$PWFILE"

genpw() { # AD complexity: upper, lower, digit, symbol, 20 chars
  local r; r="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16 || true)"
  printf '%s' "Yw${r}-7a"
}
pw_key() { local k="${1//./_}"; printf '%s' "${k^^}"; }
pw_get() { sed -n "s/^$(pw_key "$1")=//p" "$PWFILE" | head -n1; }
# Passwords travel on stdin only (never on a command line, so they are not visible in the process list).
setpw() { # user password [extra samba-tool flags]
  local u="$1" p="$2"; shift 2
  printf '%s\n%s\n' "$p" "$p" | ad samba-tool user setpassword "$u" "$@" >/dev/null 2>&1
}
pw_set() { local k; k="$(pw_key "$1")"; sed -i "/^${k}=/d" "$PWFILE"; printf '%s=%s\n' "$k" "$2" >> "$PWFILE"; }

echo "Waiting for samba-ad ..."
for _ in $(seq 1 60); do ad samba-tool domain info 127.0.0.1 >/dev/null 2>&1 && break; sleep 2; done
ad samba-tool domain info 127.0.0.1 >/dev/null

# Lab policy: passwords do not expire (max age 0), so demo accounts keep working.
if ! sam domain passwordsettings show | grep -qE 'Maximum password age \(days\): 0$'; then
  sam domain passwordsettings set --max-pwd-age=0 >/dev/null; changed "password policy max-pwd-age=0"
fi

# OUs
for ou in "OU=Yawar" "$OU_USERS" "$OU_GROUPS"; do
  if ! sam ou list | grep -qix "$ou"; then sam ou create "$ou" >/dev/null; changed "ou $ou"; fi
done

# Groups
for g in ADMINISTRATOR AUDITOR RECRUITMENT LOGISTICS HOSPITAL; do
  if ! sam group list | grep -qx "GG-YAW-$g"; then
    sam group add "GG-YAW-$g" --groupou="$OU_GROUPS" --description="Yawar staff role $g" >/dev/null; changed "group GG-YAW-$g"
  fi
done

# Set an attribute when its value differs (uses an existing AD attribute, no schema extension).
ensure_attr() { # user dn attr value
  local cur
  cur="$(sam user show "$1" --attributes="$3" | sed -n "s/^$3: //p" | head -n1 || true)"
  if [ "$cur" != "$4" ]; then
    local op="replace"; [ -z "$cur" ] && op="add"
    printf 'dn: %s\nchangetype: modify\n%s: %s\n%s: %s\n' "$2" "$op" "$3" "$3" "$4" | ad ldbmodify -H "$LDAPI" >/dev/null
    changed "$1 $3=$4"
  fi
}

USERS="$(sam user list)"
while IFS=';' read -r -u 3 user group given sn site; do
  case "$user" in ''|\#*) continue ;; esac
  extra=(); [ "$MUST" = 1 ] && extra=(--must-change-at-next-login)
  if ! grep -qx "$user" <<<"$USERS"; then
    pw="$(genpw)"; pw_set "$user" "$pw"   # stored first: a failure below never loses it
    sam user create "$user" --random-password --userou="$OU_USERS" >/dev/null
    setpw "$user" "$pw" "${extra[@]}"
    changed "user $user (password in .secrets/passwords.env)"
  elif [ "$RESET" = 1 ] || [ -z "$(pw_get "$user")" ]; then
    pw="$(genpw)"; pw_set "$user" "$pw"
    setpw "$user" "$pw" "${extra[@]}"
    changed "password reset $user"
  fi
  dn="$(sam user show "$user" --attributes=dn | sed -n 's/^dn: //p' | head -n1)"
  ensure_attr "$user" "$dn" givenName "$given"
  ensure_attr "$user" "$dn" sn "$sn"
  ensure_attr "$user" "$dn" displayName "$given $sn"
  ensure_attr "$user" "$dn" mail "$user@yawar.lab"
  ensure_attr "$user" "$dn" physicalDeliveryOfficeName "$site"
  if ! sam group listmembers "GG-YAW-$group" | grep -qx "$user"; then
    sam group addmembers "GG-YAW-$group" "$user" >/dev/null; changed "$user in GG-YAW-$group"
  fi
done 3< "$DIR/users.csv"

echo "Done: $CHANGES change(s)."
