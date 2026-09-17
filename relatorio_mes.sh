#!/bin/bash
set -euo pipefail
# Relatório acumulado do mês até agora
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="/usr/bin/python3"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/relatorio_mes.log"

mkdir -p "$LOG_DIR"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting relatorio_mes" >> "$LOG_FILE"
"$PYTHON" "$SCRIPT_DIR/main.py" report --periodo mes >> "$LOG_FILE" 2>&1
