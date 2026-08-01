#!/bin/bash
# Relatório de 24h do dia anterior
DIR="$(cd "$(dirname "$0")" && pwd)"
/usr/bin/python3 "$DIR/main.py" report --periodo 24h-anterior
