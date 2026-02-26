#!/usr/bin/env bash

set -e

echo "=== OCR Pipeline Setup ==="

# Check if ollama is installed
if command -v ollama &> /dev/null; then
  echo "Ollama already installed"
else
  echo "Installing Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh
fi

# Pull the models
echo "Pulling glm-ocr:q8_0 model..."
ollama pull glm-ocr:q8_0

echo "Pulling qwen3:4b-q8_0 model..."
ollama pull qwen3:4b-q8_0

echo "Checking Volta installation..."
if ! command -v volta >/dev/null 2>&1; then
  echo "Volta is not installed. Installing Volta..."
  curl -fsSL https://get.volta.sh | bash
  export VOLTA_HOME="$HOME/.volta"
  export PATH="$VOLTA_HOME/bin:$PATH"
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "$HOME/.volta/bin" >> "$GITHUB_PATH"
  fi
fi

echo "Installing pinned Node..."
volta install node

echo "Installing Bun..."
curl -fsSL https://bun.com/install | bash

echo "Installing dependencies..."
bun install

echo "Setup complete!"
