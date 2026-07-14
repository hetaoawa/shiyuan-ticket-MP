# Platform TLS deployment

Platform TLS uses the application's existing `server.port` connector. Do not configure
`server.ssl.*`, Nginx, or a second application port. In Docker, publish the public TLS port
directly to the application port:

```yaml
services:
  ticket-api:
    environment:
      PLATFORM_SSL_ROOT_KEY: ${PLATFORM_SSL_ROOT_KEY}
    ports:
      - "443:9860"
```

Generate the root encryption key once with `openssl rand -base64 32`, store it in the
deployment secret manager, and keep it stable across restarts. Losing or changing it makes
all stored PKCS#12 versions undecryptable. The API never returns encrypted key material,
private keys, or deploy-token hashes.

Apply Flyway migration V26 before using the management API. A database without the platform
TLS tables starts as HTTP. If persisted state requests HTTPS, missing root key, missing
certificate, decryption errors, expired certificates, or invalid PKCS#12 data abort startup
before Tomcat binds the connector (fail closed).

Use `POST /api/admin/platform/ssl/deploy-tokens` to create a short-lived token; the plaintext
is returned once and only its SHA-256 hash is stored. DNS-01 is the default challenge because
it does not require another listener. `scripts/acme-platform-ssl-deploy.sh` shows an acme.sh
certificate import. The same token can deploy renewals until it expires or is revoked.

After an import, poll `GET /api/admin/platform/ssl/operations`. An enabled connector reloads
the default SSL host config without changing ports. HTTP/HTTPS mode changes are serialized on
a background thread; Tomcat briefly stops and rebinds the same connector, and the adapter
attempts to restore the prior connector state if the transition fails.

Legacy global TLS material can enter only through
`POST /api/admin/platform/ssl/legacy-import`. The boundary closes permanently after the first
successful import and also refuses to overwrite an already selected certificate.

Run `scripts/verify-platform-tls.sh <host> [port]` from outside the container for a real SNI,
hostname, certificate-validity, and application handshake check.
