#!/usr/bin/env bash
# Downloads TDLib prebuilt (from TGX-Android/tdlib, the Telegram X official prebuilt)
# and populates the :tdlib Gradle module.
#
# Run once from the project root:
#   chmod +x scripts/setup_tdlib.sh && ./scripts/setup_tdlib.sh

set -euo pipefail

REPO="https://github.com/TGX-Android/tdlib.git"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TDLIB_MODULE="$PROJECT_DIR/tdlib"
TEMP_DIR="$(mktemp -d)"

trap 'rm -rf "$TEMP_DIR"' EXIT

echo "→ Cloning TGX-Android/tdlib (shallow, with LFS)..."
GIT_LFS_SKIP_SMUDGE=0 git clone --depth=1 "$REPO" "$TEMP_DIR/tdlib"
# If git-lfs is installed, pull the actual .so binaries (repo uses LFS for them)
if command -v git-lfs &>/dev/null; then
    git -C "$TEMP_DIR/tdlib" lfs pull
else
    echo "→ git-lfs not found - downloading .so files via LFS batch API..."
    python3 - "$TEMP_DIR/tdlib" <<'PYEOF'
import sys, json, subprocess, pathlib, urllib.request
repo_dir = pathlib.Path(sys.argv[1])
lfs_url = "https://github.com/TGX-Android/tdlib.git/info/lfs/objects/batch"
abis = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
for abi in abis:
    so = repo_dir / "src" / "main" / "libs" / abi / "libtdjni.so"
    if not so.exists():
        so = repo_dir / "src" / "main" / "jniLibs" / abi / "libtdjni.so"
    if not so.exists():
        continue
    text = so.read_text()
    if not text.startswith("version https://git-lfs"):
        continue
    oid = next(l.split(":")[1] for l in text.splitlines() if l.startswith("oid sha256:"))
    size = int(next(l.split()[1] for l in text.splitlines() if l.startswith("size")))
    payload = json.dumps({"operation":"download","transfers":["basic"],"objects":[{"oid":oid,"size":size}]}).encode()
    req = urllib.request.Request(lfs_url, data=payload, headers={"Accept":"application/vnd.git-lfs+json","Content-Type":"application/vnd.git-lfs+json"})
    resp = json.loads(urllib.request.urlopen(req).read())
    url = resp["objects"][0]["actions"]["download"]["href"]
    print(f"  Downloading {abi}/libtdjni.so ...")
    urllib.request.urlretrieve(url, so)
PYEOF
fi

SRC="$TEMP_DIR/tdlib/src/main"

echo "→ Copying Java API (org.drinkless.tdlib)..."
JAVA_DST="$TDLIB_MODULE/src/main/java/org/drinkless/tdlib"
mkdir -p "$JAVA_DST"
cp "$SRC/java/org/drinkless/tdlib/Client.java" "$JAVA_DST/"
cp "$SRC/java/org/drinkless/tdlib/TdApi.java"  "$JAVA_DST/"

echo "→ Copying native libraries..."
JNI_DST="$TDLIB_MODULE/src/main/jniLibs"
mkdir -p "$JNI_DST"
# TGX-Android stores .so files under src/main/libs/<abi>/
if [ -d "$SRC/libs" ]; then
    cp -r "$SRC/libs/." "$JNI_DST/"
elif [ -d "$SRC/jniLibs" ]; then
    cp -r "$SRC/jniLibs/." "$JNI_DST/"
fi

echo "→ Copying OpenSSL libraries (libsslx.so, libcryptox.so)..."
# libtdjni.so links against libsslx.so and libcryptox.so (renamed OpenSSL to avoid conflicts)
OPENSSL_SRC="$TEMP_DIR/tdlib/openssl"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -d "$OPENSSL_SRC/$abi/lib" ]; then
        mkdir -p "$JNI_DST/$abi"
        cp "$OPENSSL_SRC/$abi/lib/libsslx.so"    "$JNI_DST/$abi/" 2>/dev/null || true
        cp "$OPENSSL_SRC/$abi/lib/libcryptox.so" "$JNI_DST/$abi/" 2>/dev/null || true
    fi
done

echo "→ Done. TDLib module is ready."
echo ""
echo "Next: add telegram.api_id and telegram.api_hash to local.properties, then build."
