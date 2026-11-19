#!/usr/bin/env bash
# Downloads the Sherpa-ONNX Android library (offline wake word) into app/libs.
set -euo pipefail
V=2.0.2
mkdir -p "$(dirname "$0")/../app/libs"
curl -fL -o "$(dirname "$0")/../app/libs/sherpa-onnx-$V.aar" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-$V.aar"
