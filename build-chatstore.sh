#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/runtime/assets
linker=()
if [[ "$(uname -m)" != aarch64 ]]; then
    linker=(-C "linker=${CODEX_CHATSTORE_LINKER:-aarch64-linux-gnu-gcc}")
fi
rustc --edition=2021 --target aarch64-unknown-linux-gnu -C opt-level=3 -C strip=symbols \
    -C target-feature=+crt-static "${linker[@]}" runtime/chatstore.rs -o build/runtime/assets/chatstore.bin
