#!/usr/bin/env sh
set -eu

host=${1:?host is required}
port=${2:-443}

# Real network handshake check kept outside the unit suite. It checks SNI, hostname,
# certificate dates, and the application response on the same public connector.
openssl s_client -connect "${host}:${port}" -servername "${host}" -verify_hostname "${host}" </dev/null
curl --fail --show-error --silent "https://${host}:${port}/api/system/version"
