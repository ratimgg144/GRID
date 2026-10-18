#!/usr/bin/env bash
set -euo pipefail
# Run locally once; store the .jks securely and NEVER commit it.
keytool -genkeypair -v -storetype PKCS12 -keystore grid-release.jks -alias grid \
  -keyalg RSA -keysize 3072 -validity 10000
printf '\nBack up grid-release.jks and both passwords offline! Losing the key prevents updating installed release APKs.\n'
printf 'GitHub secret GRID_KEYSTORE_BASE64 content: base64 -w0 grid-release.jks (Linux)\n'
