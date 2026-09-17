#!/bin/bash
set -euo pipefail
# Monitoramento de máquinas offline e alertas via WhatsApp
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="/usr/bin/python3"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/monitoramento.log"

mkdir -p "$LOG_DIR"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting monitoramento" >> "$LOG_FILE"
"$PYTHON" "$SCRIPT_DIR/main.py" monitor >> "$LOG_FILE" 2>&1
