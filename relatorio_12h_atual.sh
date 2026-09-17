#!/bin/bash
set -euo pipefail
# Relatório de 12h do dia atual (últimas 12 horas)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="/usr/bin/python3"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/relatorio_12h_atual.log"

mkdir -p "$LOG_DIR"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting relatorio_12h_atual" >> "$LOG_FILE"
"$PYTHON" "$SCRIPT_DIR/main.py" report --periodo 12h-atual >> "$LOG_FILE" 2>&1
