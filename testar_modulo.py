#!/usr/bin/env python3
"""
PLAY BOX BR - Painel de Testes MQTT Interativo
Permite escutar pings em tempo real e disparar comandos de credito para o modulo.
"""

import sys
import time
import json
import uuid
import paho.mqtt.client as mqtt

BROKER_HOST = "broker.hivemq.com"
BROKER_PORT = 1883
DEVICE_ID = "PB-00001"

TOPIC_STATUS = f"playbox/maquinas/{DEVICE_ID}/status"
TOPIC_CMD = f"playbox/maquinas/{DEVICE_ID}/comando"

print("=" * 60)
print(f"🎮 PLAY BOX BR - PAINEL INTERATIVO DE TESTES MQTT")
print(f"📡 Conectando ao Broker: {BROKER_HOST}:{BROKER_PORT}")
print(f"🏷️  Dispositivo Alvo:    {DEVICE_ID}")
print("=" * 60)

def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print(f"\n[OK] Conectado ao Broker com sucesso!")
        client.subscribe(TOPIC_STATUS)
        print(f"[OK] Escutando tópico: {TOPIC_STATUS}")
        print("\nAguardando pings do módulo ou envie comandos abaixo:")
        print("  [1] Enviar 1 Pulso (1 Crédito)")
        print("  [2] Enviar 2 Pulsos (2 Créditos)")
        print("  [3] Enviar 3 Pulsos (3 Créditos)")
        print("  [r] Reset remoto de configurações")
        print("  [q] Sair")
        print("-" * 60)
    else:
        print(f"[ERRO] Falha na conexão com o broker (código {rc})")

def on_message(client, userdata, msg):
    try:
        data = json.loads(msg.payload.decode('utf-8'))
        evento = data.get("evento") or data.get("status")
        
        if evento == "ping":
            rssi = data.get("rssi")
            pulsos = data.get("total_digitais", data.get("total_pulsos", 0))
            entrada = data.get("relogio_entrada")
            saida = data.get("relogio_saida")
            extra = f" | Moedas: {entrada} | Pelúcias: {saida}" if entrada is not None else ""
            print(f"\n📥 [PING RECEBIDO] Módulo: {data.get('maquina_id')} | Sinal: {rssi} dBm | Créditos Digitais: {pulsos}{extra}")
        elif evento == "ok":
            tx = data.get("tx_id")
            liberados = data.get("pulsos_liberados")
            total = data.get("total_digitais_acumulado", data.get("total_acumulado", 0))
            print(f"\n⚡ [CRÉDITO CONFIRMADO!] Tx: {tx} | Liberados: {liberados} jogada(s) | Total acumulado: {total}")
        elif evento == "online":
            print(f"\n🟢 [MÓDULO ONLINE] ID: {data.get('maquina_id')} | IP Local: {data.get('ip')}")
        else:
            print(f"\n📬 [MENSAGEM] {msg.payload.decode('utf-8')}")
    except Exception:
        print(f"\n📬 [RAW] {msg.payload.decode('utf-8')}")

client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"playbox-tester-{uuid.uuid4().hex[:6]}")
client.on_connect = on_connect
client.on_message = on_message

try:
    client.connect(BROKER_HOST, BROKER_PORT, keepalive=60)
    client.loop_start()
except Exception as e:
    print(f"[ERRO] Não foi possível conectar ao broker: {e}")
    sys.exit(1)

try:
    while True:
        opcao = input().strip().lower()
        if not opcao:
            continue
        
        if opcao == "q":
            print("Encerrando...")
            break
        elif opcao in ("1", "2", "3"):
            qtd = int(opcao)
            tx = f"TX-{int(time.time())}"
            payload = json.dumps({"cmd": "pulse", "qty": qtd, "tx_id": tx})
            client.publish(TOPIC_CMD, payload)
            print(f"🚀 [ENVIADO] Comando de {qtd} pulso(s) enviado! (Tx: {tx})")
        elif opcao == "r":
            payload = json.dumps({"cmd": "reset_config"})
            client.publish(TOPIC_CMD, payload)
            print(f"⚠️ [ENVIADO] Comando de RESET remoto enviado para o módulo!")
        else:
            print("Opção inválida. Digite 1, 2, 3, r ou q.")
except KeyboardInterrupt:
    print("\nEncerrando...")
finally:
    client.loop_stop()
    client.disconnect()
