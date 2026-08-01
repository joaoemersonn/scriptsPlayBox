#!/bin/bash
# Relatório acumulado do mês até agora
DIR="$(cd "$(dirname "$0")" && pwd)"
/usr/bin/python3 "$DIR/main.py" report --periodo mes
