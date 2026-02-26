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
echo "Pulling glm-ocr model..."
ollama pull glm-ocr

echo "Pulling qwen3:1.7b-q4_K_M model..."
ollama pull qwen3:1.7b-q4_K_M

# Check if Node.js is installed
if command -v node &> /dev/null; then
  NODE_VERSION=$(node --version 2>&1 | sed 's/v//')
  MAJOR_VERSION=$(echo $NODE_VERSION | cut -d. -f1)
  echo "Node.js version: $NODE_VERSION"
  if [ "$MAJOR_VERSION" -lt 18 ]; then
    echo "Node.js version is below 18. Please upgrade to Node.js 18+"
    exit 1
  fi
else
  echo "Node.js not found. Installing..."
  if [[ "$OSTYPE" == "darwin"* ]]; then
    brew install node
  else
    curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -
    apt-get install -y nodejs
  fi
fi

# Check if pnpm is installed
if ! command -v pnpm &> /dev/null; then
  echo "Installing pnpm..."
  npm install -g pnpm
fi

echo "Installing dependencies..."
pnpm install

echo "Setup complete!"
