#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
termux=/data/data/com.termux/files
install_dir="$termux/home/.local/share/codex-mobile"
props="$termux/home/.termux/termux.properties"
install -d "$install_dir" "$(dirname "$props")"
install -m 700 launch-termux.sh "$install_dir/start"
printf 'CODEX_MOBILE_PROJECT=%q\n' "$PWD" > "$install_dir/install.env"
if ! grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*$' "$props" 2>/dev/null; then
  if grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=' "$props" 2>/dev/null; then
    echo "Ative allow-external-apps=true em $props e recarregue as configurações do Termux."
    exit 1
  fi
  printf '\nallow-external-apps = true\n' >> "$props"
fi
mkdir -p '/sdcard/codex-android/arquivos do chat'
