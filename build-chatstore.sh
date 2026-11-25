#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/runtime/assets
linker=()
if [[ "$(uname -m)" != aarch64 ]]; then
    linker=(-C "linker=${CODEX_CHATSTORE_LINKER:-aarch64-linux-gnu-gcc}")
fi
export CARGO_ENCODED_RUSTFLAGS
CARGO_ENCODED_RUSTFLAGS="$(printf '%s\x1f' -C target-feature=+crt-static "${linker[@]}")"
CARGO_ENCODED_RUSTFLAGS="${CARGO_ENCODED_RUSTFLAGS%$'\x1f'}"
cargo build --locked --release --target aarch64-unknown-linux-gnu --manifest-path runtime/Cargo.toml "$@"
cp runtime/target/aarch64-unknown-linux-gnu/release/chatstore build/runtime/assets/chatstore.bin
