# Samba AD domain controller for the local Yawar environment (YAW-30, ADR-042).
# Small image we control: pinned Debian base + the distribution's Samba. Local lab use only.
FROM debian:12.13-slim@sha256:67b30a61dc87758f0caf819646104f29ecbda97d920aaf5edc834128ac8493d3

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      samba samba-ad-dc samba-ad-provision samba-dsdb-modules samba-vfs-modules winbind \
      krb5-user ldb-tools ldap-utils openssl ca-certificates \
 && rm -rf /var/lib/apt/lists/* /etc/samba/smb.conf

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

VOLUME ["/var/lib/samba"]
EXPOSE 636
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
