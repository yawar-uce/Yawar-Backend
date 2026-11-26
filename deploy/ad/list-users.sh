#!/usr/bin/env bash
# Runs inside the samba-ad container (make ad-users). Never prints passwords.
printf '%-16s %-26s %s\n' USER GROUP SITE
ldbsearch -H /var/lib/samba/private/sam.ldb -b "OU=Personal,OU=Yawar,DC=yawar,DC=lab" '(objectClass=user)' \
    sAMAccountName memberOf physicalDeliveryOfficeName 2>/dev/null \
  | awk '
    /^sAMAccountName:/ {u=$2}
    /^memberOf:/ {sub(/^memberOf: CN=/,""); sub(/,.*/,""); g=$0}
    /^physicalDeliveryOfficeName:/ {s=$2}
    /^$/ { if (u!="") printf "%-16s %-26s %s\n", u, g, s; u=g=s="" }
    END { if (u!="") printf "%-16s %-26s %s\n", u, g, s }' | sort
