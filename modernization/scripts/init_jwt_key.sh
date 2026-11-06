#!/bin/sh
# Create the RSA key that identity-service signs its access tokens with
# (RS256; the other services read the public half from its JWKS endpoint).
# An existing key is kept, so tokens and sessions survive a rerun of setup.
#
# Usage: init_jwt_key.sh KEY_FILE
set -eu

key=${1:?usage: init_jwt_key.sh KEY_FILE}
dir=$(dirname "$key")
umask 077
mkdir -p "$dir"
chmod 700 "$dir"

if [ -s "$key" ]; then
	if ! openssl pkey -in "$key" -noout 2>/dev/null; then
		printf 'FAIL  %-16s %s\n' jwt-key "$key is not a readable private key; move it away and rerun setup" >&2
		exit 1
	fi
	chmod 600 "$key"
	printf 'ok    %-16s %s\n' jwt-key "$key: kept (mode 600)"
	exit 0
fi

command -v openssl >/dev/null 2>&1 || {
	printf 'FAIL  %-16s %s\n' jwt-key 'openssl not found' >&2
	exit 1
}
tmp="$key.tmp.$$"
trap 'rm -f "$tmp"' EXIT
# PKCS#8 PEM ("BEGIN PRIVATE KEY"), which Spring and Nimbus read directly.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp" 2>/dev/null
chmod 600 "$tmp"
mv "$tmp" "$key"
trap - EXIT
printf 'ok    %-16s %s\n' jwt-key "$key: generated RSA 2048 (mode 600)"
