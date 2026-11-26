#!/bin/bash
# Provisions yawar.lab on first start (state in /var/lib/samba), then runs the domain controller.
# Docker Desktop denies security.* xattrs, so NT ACLs go to posix:eadb (posix:eadb).
set -euo pipefail

REALM="${AD_REALM:-YAWAR.LAB}"
DOMAIN="${AD_DOMAIN:-YAWAR}"
TLS_DIR=/var/lib/samba/tls
: "${AD_ADMIN_PASSWORD:?AD_ADMIN_PASSWORD is required}"
if [ "${AD_ADMIN_PASSWORD}" = "CHANGE_ME" ]; then
  echo "AD_ADMIN_PASSWORD is still the placeholder CHANGE_ME: set a real value in deploy/local/.env" >&2; exit 1
fi
classes=0
[[ "${AD_ADMIN_PASSWORD}" =~ [A-Z] ]] && classes=$((classes + 1))
[[ "${AD_ADMIN_PASSWORD}" =~ [a-z] ]] && classes=$((classes + 1))
[[ "${AD_ADMIN_PASSWORD}" =~ [0-9] ]] && classes=$((classes + 1))
[[ "${AD_ADMIN_PASSWORD}" =~ [^A-Za-z0-9] ]] && classes=$((classes + 1))
if [ "${#AD_ADMIN_PASSWORD}" -lt 8 ] || [ "$classes" -lt 3 ]; then
  echo "AD_ADMIN_PASSWORD fails AD complexity: need 8+ characters from at least 3 of upper, lower, digit, symbol" >&2; exit 1
fi

if [ ! -f /var/lib/samba/private/sam.ldb ]; then
  echo "Provisioning ${REALM} (first start)"
  rm -f /etc/samba/smb.conf
  samba-tool domain provision --use-rfc2307 --server-role=dc --dns-backend=SAMBA_INTERNAL \
    --realm="${REALM}" --domain="${DOMAIN}" --adminpass="${AD_ADMIN_PASSWORD}" \
    --option="dns forwarder = 127.0.0.11" --option="posix:eadb = /var/lib/samba/private/eadb.tdb" \
    --option="tls enabled = yes" \
    --option="tls cafile = ${TLS_DIR}/ca.pem" \
    --option="tls certfile = ${TLS_DIR}/cert.pem" \
    --option="tls keyfile = ${TLS_DIR}/key.pem"
  mv /etc/samba/smb.conf /var/lib/samba/smb.conf
fi

# The container layer is disposable: keep smb.conf in the volume and relink config on every start.
ln -sf /var/lib/samba/smb.conf /etc/samba/smb.conf
cp /var/lib/samba/private/krb5.conf /etc/krb5.conf

# Lab CA + server certificate whose names include the compose service name (Keycloak connects to samba-ad:636).
if [ ! -f "${TLS_DIR}/cert.pem" ]; then
  mkdir -p "${TLS_DIR}"
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj "/CN=Yawar Lab AD CA" \
    -keyout "${TLS_DIR}/ca.key" -out "${TLS_DIR}/ca.pem" 2>/dev/null
  openssl req -newkey rsa:3072 -nodes -subj "/CN=samba-ad.${REALM,,}" \
    -keyout "${TLS_DIR}/key.pem" -out "${TLS_DIR}/cert.csr" 2>/dev/null
  printf 'subjectAltName=DNS:samba-ad,DNS:samba-ad.%s,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' "${REALM,,}" > "${TLS_DIR}/san.ext"
  openssl x509 -req -in "${TLS_DIR}/cert.csr" -CA "${TLS_DIR}/ca.pem" -CAkey "${TLS_DIR}/ca.key" \
    -CAcreateserial -days 3650 -extfile "${TLS_DIR}/san.ext" -out "${TLS_DIR}/cert.pem" 2>/dev/null
  chmod 600 "${TLS_DIR}/key.pem" "${TLS_DIR}/ca.key"
  chmod 644 "${TLS_DIR}/ca.pem" "${TLS_DIR}/cert.pem"
fi

exec samba --foreground --no-process-group --debug-stdout
