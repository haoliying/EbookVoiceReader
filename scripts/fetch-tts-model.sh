#!/usr/bin/env bash
# 下载中英双语语音模型（约 163MB）到 app/src/main/assets/tts/zh_en-melo/
# 该模型超过 GitHub 单文件限制，因此不随仓库提交，构建前需先执行本脚本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/tts/zh_en-melo"
URL="https://hf-mirror.com/csukuangfj/vits-melo-tts-zh_en/resolve/main/model.onnx"

mkdir -p "$DEST"

if [ -f "$DEST/model.onnx" ] && [ "$(stat -c%s "$DEST/model.onnx")" -gt 100000000 ]; then
  echo "模型已存在：$DEST/model.onnx"
  exit 0
fi

echo "正在下载 vits-melo-tts-zh_en model.onnx …"
curl -L --fail --retry 3 -o "$DEST/model.onnx.part" "$URL"
mv "$DEST/model.onnx.part" "$DEST/model.onnx"
echo "完成：$DEST/model.onnx"
