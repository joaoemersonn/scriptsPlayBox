#!/bin/bash
# Relatório de 12h do dia atual (últimas 12 horas)
DIR="$(cd "$(dirname "$0")" && pwd)"
/usr/bin/python3 "$DIR/main.py" report --periodo 12h-atual
