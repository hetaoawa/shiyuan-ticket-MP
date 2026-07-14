#!/usr/bin/env sh
set -eu

# acme.sh example:
#   acme.sh --issue --dns dns_cf -d example.com
#   PLATFORM_SSL_DEPLOY_URL=https://host/api/platform/ssl/deploy/import \
#   PLATFORM_SSL_DEPLOY_TOKEN=... \
#     ./scripts/acme-platform-ssl-deploy.sh example.com \
#       ~/.acme.sh/example.com_ecc/fullchain.cer ~/.acme.sh/example.com_ecc/example.com.key
#
# DNS-01 is the platform default. This hook never needs an HTTP challenge port.

domain=${1:?domain is required}
fullchain=${2:?full-chain PEM path is required}
private_key=${3:?private-key PEM path is required}
: "${PLATFORM_SSL_DEPLOY_URL:?PLATFORM_SSL_DEPLOY_URL is required}"
: "${PLATFORM_SSL_DEPLOY_TOKEN:?PLATFORM_SSL_DEPLOY_TOKEN is required}"

curl --fail --silent --show-error \
  --request POST \
  --header "X-Deploy-Token: ${PLATFORM_SSL_DEPLOY_TOKEN}" \
  --form "certificate=@${fullchain};type=application/x-pem-file" \
  --form "privateKey=@${private_key};type=application/x-pem-file" \
  --form-string "domain=${domain}" \
  "${PLATFORM_SSL_DEPLOY_URL}"
