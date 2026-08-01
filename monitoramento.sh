#!/bin/bash
# Monitoramento de máquinas offline e alertas via WhatsApp
DIR="$(cd "$(dirname "$0")" && pwd)"
/usr/bin/python3 "$DIR/main.py" monitor
