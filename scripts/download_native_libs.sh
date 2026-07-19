#!/bin/bash
# Download sherpa-onnx native libraries for Android
# Usage: ./scripts/download_native_libs.sh [version]

set -e

VERSION="${1:-v1.13.4}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"
TMP_DIR="/tmp/sherpa-onnx-android"

echo "=== Downloading sherpa-onnx $VERSION native libraries ==="

# Check if already downloaded
if [ -f "$JNILIBS_DIR/arm64-v8a/libsherpa-onnx-jni.so" ]; then
    echo "Native libraries already exist. Skipping download."
    exit 0
fi

mkdir -p "$TMP_DIR" "$JNILIBS_DIR"

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/$VERSION/sherpa-onnx-$VERSION-android.tar.bz2"
echo "Downloading from: $URL"

cd "$TMP_DIR"
wget -q "$URL" -O sherpa-onnx-android.tar.bz2
echo "Extracting..."
tar xf sherpa-onnx-android.tar.bz2

# Copy .so files to jniLibs
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -d "jniLibs/$arch" ]; then
        mkdir -p "$JNILIBS_DIR/$arch"
        cp -v jniLibs/$arch/*.so "$JNILIBS_DIR/$arch/" 2>/dev/null || true
        echo "✓ Copied $arch libraries"
    fi
done

# Clean up
rm -rf "$TMP_DIR"

echo "=== Native libraries downloaded successfully ==="
ls -la "$JNILIBS_DIR"/arm64-v8a/ 2>/dev/null || echo "Warning: arm64-v8a not found"
