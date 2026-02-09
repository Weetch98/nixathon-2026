#!/usr/bin/env bash

set -euo pipefail

# Example: DEFAULT_SSH_TARGET="ubuntu@203.0.113.10"
DEFAULT_SSH_TARGET=""
DEFAULT_CONTEXT_NAME="nixathon-vps"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required but was not found in PATH." >&2
  exit 1
fi

if [ "$#" -gt 2 ]; then
  echo "Usage: $0 [ssh-user@vps-host] [context-name]" >&2
  echo "Example: $0 ubuntu@203.0.113.10 nixathon-vps" >&2
  echo "Example (existing context): $0" >&2
  exit 1
fi

SSH_TARGET="${1:-$DEFAULT_SSH_TARGET}"
CONTEXT_NAME="${2:-$DEFAULT_CONTEXT_NAME}"

if ! docker context inspect "$CONTEXT_NAME" >/dev/null 2>&1; then
  if [ -z "$SSH_TARGET" ]; then
    echo "Context '$CONTEXT_NAME' does not exist." >&2
    echo "Provide <ssh-user@vps-host> as first argument or set DEFAULT_SSH_TARGET in scripts/deploy-vps.sh." >&2
    exit 1
  fi

  echo "Creating docker context '$CONTEXT_NAME' for ssh://$SSH_TARGET"
  docker context create "$CONTEXT_NAME" --docker "host=ssh://$SSH_TARGET"
else
  echo "Using existing docker context '$CONTEXT_NAME'"
fi

echo "Deploying with docker compose on context '$CONTEXT_NAME'..."
docker --context "$CONTEXT_NAME" compose up -d --build

echo "Deployment completed."
docker --context "$CONTEXT_NAME" compose ps
