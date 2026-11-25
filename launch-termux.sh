#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
location="$(dirname "$0")"
source "$location/install.env"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
[[ "${1:-}" =~ ^[a-f0-9]{64}$ ]]
exec proot-distro login --bind /sdcard:/sdcard ubuntu -- /bin/bash -lc 'cd "$1" && CODEX_MOBILE_TOKEN="$2" exec node server.mjs' codex-mobile "$CODEX_MOBILE_PROJECT" "$1"
