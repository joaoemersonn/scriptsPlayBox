#!/bin/bash
set -euo pipefail
# Relatório de 24h do dia anterior
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="/usr/bin/python3"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/relatorio_24h.log"

mkdir -p "$LOG_DIR"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting relatorio_24h" >> "$LOG_FILE"
"$PYTHON" "$SCRIPT_DIR/main.py" report --periodo 24h-anterior >> "$LOG_FILE" 2>&1
