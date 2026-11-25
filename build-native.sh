#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export CODEX_MOBILE_KEYSTORE="${CODEX_MOBILE_KEYSTORE:-$HOME/.local/state/codex-mobile/signing.p12}"
[[ -f "$CODEX_MOBILE_KEYSTORE" ]] || {
    mkdir -p "$(dirname "$CODEX_MOBILE_KEYSTORE")"
    keytool -genkeypair -keystore "$CODEX_MOBILE_KEYSTORE" -alias codex-mobile -storepass android \
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Codex Mobile Local'
}
./prepare-runtime.sh
exec "${GRADLE_BIN:-gradle}" :app:assembleRelease "$@"
