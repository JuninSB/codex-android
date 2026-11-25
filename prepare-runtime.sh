#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cache="$PWD/.build/runtime"
output="$PWD/build/runtime"
mkdir -p "$cache/packages" "$output/assets" "$output/jniLibs/arm64-v8a"

fetch() {
    if [[ ! -s "$2" ]]; then
        curl -fLsS --retry 2 "$1" -o "$2.part"
        mv "$2.part" "$2"
    fi
}
fetch https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz "$cache/ubuntu.tar.gz"
fetch https://nodejs.org/dist/v24.15.0/node-v24.15.0-linux-arm64.tar.xz "$cache/node.tar.xz"
fetch https://registry.npmjs.org/@openai/codex/-/codex-0.167.1-linux-arm64.tgz "$cache/codex.tgz"

if [[ ! -s "$cache/tools.tar.gz" ]]; then
    stage="$(mktemp -d "$cache/package.XXXXXX")"
    mkdir -p "$stage/root/opt/node" "$stage/root/opt/codex"
    tar -xJf "$cache/node.tar.xz" -C "$stage/root/opt/node" --strip-components=1 \
        node-v24.15.0-linux-arm64/bin node-v24.15.0-linux-arm64/lib \
        node-v24.15.0-linux-arm64/LICENSE
    tar -xzf "$cache/codex.tgz" -C "$stage/root/opt/codex" --strip-components=3 \
        package/vendor/aarch64-unknown-linux-musl/bin \
        package/vendor/aarch64-unknown-linux-musl/codex-path \
        package/vendor/aarch64-unknown-linux-musl/codex-resources/bwrap
    tar -czf "$cache/tools.tar.gz.part" -C "$stage/root" opt
    mv "$cache/tools.tar.gz.part" "$cache/tools.tar.gz"
fi

fetch https://curl.se/ca/cacert.pem "$cache/ca-certificates.crt"
cp "$cache/ubuntu.tar.gz" "$output/assets/ubuntu.tgz"
cp "$cache/tools.tar.gz" "$output/assets/tools.tgz"
cp "$cache/ca-certificates.crt" server.mjs "$output/assets/"
bash ./build-chatstore.sh
termux_repo=https://packages.termux.dev/apt/termux-main/pool/main
fetch "$termux_repo/p/proot/proot_5.1.107.102_aarch64.deb" "$cache/packages/proot.deb"
fetch "$termux_repo/libt/libtalloc/libtalloc_2.5.0_aarch64.deb" "$cache/packages/libtalloc.deb"
fetch "$termux_repo/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" "$cache/packages/libandroid-shmem.deb"
(
    cd "$cache/packages"
    sha256sum --check --status <<'SUMS'
10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030  proot.deb
556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7  libtalloc.deb
0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6  libandroid-shmem.deb
SUMS
    for deb in proot.deb libtalloc.deb libandroid-shmem.deb; do dpkg-deb -x "$deb" extracted; done
)
prefix="$cache/packages/extracted/data/data/com.termux/files/usr"
cp "$prefix/bin/proot" "$output/jniLibs/arm64-v8a/libproot.so"
cp "$prefix/libexec/proot/loader" "$output/jniLibs/arm64-v8a/libproot_loader.so"
cp -L "$prefix/lib/libtalloc.so.2" "$output/jniLibs/arm64-v8a/libtalloc.so"
cp -L "$prefix/lib/libandroid-shmem.so" "$output/jniLibs/arm64-v8a/libandroid-shmem.so"
if command -v patchelf >/dev/null; then
    patchelf --replace-needed libtalloc.so.2 libtalloc.so "$output/jniLibs/arm64-v8a/libproot.so"
else
    node --input-type=module - "$output/jniLibs/arm64-v8a/libproot.so" <<'JS'
import fs from 'node:fs';
const file = process.argv[2];
const binary = fs.readFileSync(file);
const source = Buffer.from('libtalloc.so.2\0');
const index = binary.indexOf(source);
if (index < 0 || binary.lastIndexOf(source) !== index) throw Error('DT_NEEDED unexpected');
Buffer.from('libtalloc.so\0\0\0').copy(binary, index);
fs.writeFileSync(file, binary);
JS
fi
echo "Runtime ARM64 empacotado em $output"
