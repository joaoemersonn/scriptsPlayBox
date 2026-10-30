/*
 * PLAY BOX BR - FIRMWARE OFICIAL DEFINITIVO
 * 
 * Hardware: ESP8266 (Módulo Pixmaq / Base para novo módulo)
 * - Hotspot Wi-Fi: "PlayBox-BR" (Rede Aberta)
 * - Endereço Web: 192.168.4.1 ou playbox.setup
 * - Telinha OLED: SDA=GPIO 0, SCL=GPIO 2, Addr=0x3C
 * - Relé de Moedeiro: GPIO 5
 * - Servidor Web Nativo Ultrarrápido em Português (Sem dependência de bibliotecas bloqueantes)
 * - Abas: Configurar | Atualizar OTA | Status
 * - Atualização OTA Remota via MQTT + Local via Web e ArduinoOTA
 */

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <DNSServer.h>
#include <ESP8266mDNS.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <ESP8266httpUpdate.h>
#include <ArduinoOTA.h>
extern "C" {
  #include "user_interface.h"
}

// --- PINAGEM & HARDWARE ---
const int PIN_RELE = 5;
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// --- DADOS DE CONFIGURACAO ---
char wifi_ssid[33]     = "";
char wifi_password[65] = "";
char device_id[21]     = "PB-00001";
char mqtt_server[65]   = "192.168.1.19";
int  mqtt_port         = 1883;
char mqtt_user[33]     = "";
char mqtt_password[65] = "";

// --- SERVIDORES & CLIENTES ---
ESP8266WebServer server(80);
DNSServer dnsServer;
WiFiClient espClient;
PubSubClient mqttClient(espClient);

// --- ESTADOS DO SISTEMA ---
bool portalActive = false;
unsigned long lastHeartbeat = 0;
int totalPulsosExecutados = 0;

// --- GERENCIAMENTO DE ESCANEAMENTO WI-FI (ASSINCRONO & SEM TRAVAMENTOS) ---
struct ScannedNetwork {
  String ssid;
  int32_t rssi;
  bool encrypted;
};

std::vector<ScannedNetwork> scanResults;
bool scanInProgress = false;
unsigned long lastScanTime = 0;

void onScanComplete(int networksFound) {
  scanInProgress = false;
  lastScanTime = millis();
  scanResults.clear();

  if (networksFound > 0) {
    std::vector<int> indices;
    for (int i = 0; i < networksFound; i++) indices.push_back(i);
    std::sort(indices.begin(), indices.end(), [](int a, int b) {
      return WiFi.RSSI(a) > WiFi.RSSI(b);
    });

    std::vector<String> seen;
    for (int idx : indices) {
      String s = WiFi.SSID(idx);
      s.trim();
      if (s.length() == 0) continue;
      bool dup = false;
      for (const String& x : seen) {
        if (x == s) { dup = true; break; }
      }
      if (dup) continue;
      seen.push_back(s);

      ScannedNetwork net;
      net.ssid = s;
      net.rssi = WiFi.RSSI(idx);
      net.encrypted = (WiFi.encryptionType(idx) != ENC_TYPE_NONE);
      scanResults.push_back(net);
    }
  }
  WiFi.scanDelete();
  Serial.printf(">>> Escaneamento assincrono finalizado: %d redes unicas encontradas.\n", (int)scanResults.size());
}

void triggerWifiScan(bool force = false) {
  if (scanInProgress) return;
  if (!force && scanResults.size() > 0 && (millis() - lastScanTime < 300000)) return;

  scanInProgress = true;
  WiFi.scanNetworksAsync(onScanComplete);
  Serial.println(">>> Escaneamento de redes Wi-Fi iniciado em background...");
}

// --- FUNCOES DO DISPLAY OLED ---
void desenhaTela(const char* status, const char* linha1, const char* linha2, const char* linha3 = "") {
  display.clearDisplay();
  display.drawRect(0, 0, 128, 64, SSD1306_WHITE);

  // Cabecalho
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(6, 5);
  display.print("PLAY BOX BR ");
  display.print("[");
  display.print(device_id);
  display.println("]");
  display.drawFastHLine(4, 16, 120, SSD1306_WHITE);

  // Conteudo
  display.setCursor(6, 20);
  display.print("St: ");
  display.println(status);

  display.setCursor(6, 31);
  display.println(linha1);

  display.setCursor(6, 42);
  display.println(linha2);

  if (strlen(linha3) > 0) {
    display.setCursor(6, 53);
    display.println(linha3);
  }

  display.display();
}

// --- GERENCIAMENTO DE MEMORIA PERMANENTE (LittleFS) ---
void carregarParametros() {
  if (!LittleFS.begin()) {
    LittleFS.format();
    LittleFS.begin();
  }

  if (LittleFS.exists("/config.json")) {
    File f = LittleFS.open("/config.json", "r");
    if (f) {
      StaticJsonDocument<512> doc;
      DeserializationError err = deserializeJson(doc, f);
      f.close();

      if (!err) {
        strlcpy(wifi_ssid, doc["ssid"] | "", sizeof(wifi_ssid));
        strlcpy(wifi_password, doc["password"] | "", sizeof(wifi_password));
        strlcpy(device_id, doc["device_id"] | "PB-00001", sizeof(device_id));
        strlcpy(mqtt_server, doc["mqtt_server"] | "192.168.1.19", sizeof(mqtt_server));
        mqtt_port = doc["mqtt_port"] | 1883;
        strlcpy(mqtt_user, doc["mqtt_user"] | "", sizeof(mqtt_user));
        strlcpy(mqtt_password, doc["mqtt_password"] | "", sizeof(mqtt_password));

        Serial.printf("Configuracao carregada! SSID: %s | ID: %s | Broker: %s:%d\n", wifi_ssid, device_id, mqtt_server, mqtt_port);
      }
    }
  }
}

void salvarParametros() {
  StaticJsonDocument<512> doc;
  doc["ssid"] = wifi_ssid;
  doc["password"] = wifi_password;
  doc["device_id"] = device_id;
  doc["mqtt_server"] = mqtt_server;
  doc["mqtt_port"] = mqtt_port;
  doc["mqtt_user"] = mqtt_user;
  doc["mqtt_password"] = mqtt_password;

  File f = LittleFS.open("/config.json", "w");
  if (f) {
    serializeJson(doc, f);
    f.close();
    Serial.println("Configuracao salva no LittleFS!");
  }
}

// --- ATUALIZACAO DE FIRMWARE REMOTA (CLOUD OTA VIA MQTT) ---
void executarAtualizacaoRemotaOTA(const char* url) {
  desenhaTela("ATUALIZANDO OTA", "Baixando versao...", "Nao desligue!");
  Serial.print("Iniciando OTA remota via URL: ");
  Serial.println(url);

  StaticJsonDocument<150> doc;
  doc["evento"] = "ota_start";
  doc["maquina_id"] = device_id;
  doc["url"] = url;
  char buf[150];
  serializeJson(doc, buf);
  String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
  mqttClient.publish(statusTopic.c_str(), buf);

  mqttClient.disconnect();
  delay(200);

  ESPhttpUpdate.setLedPin(-1);
  ESPhttpUpdate.closeConnectionsOnUpdate(true);

  WiFiClient updateClient;
  t_httpUpdate_return ret = ESPhttpUpdate.update(updateClient, url);

  switch (ret) {
    case HTTP_UPDATE_FAILED:
      Serial.printf("Falha no OTA (%d): %s\n", ESPhttpUpdate.getLastError(), ESPhttpUpdate.getLastErrorString().c_str());
      desenhaTela("ERRO NO OTA", ESPhttpUpdate.getLastErrorString().c_str(), "Retomando normal...");
      delay(3000);
      break;
    case HTTP_UPDATE_NO_UPDATES:
      Serial.println("OTA: Nenhuma atualizacao encontrada.");
      desenhaTela("OTA INFO", "Ja atualizado!", "");
      delay(2000);
      break;
    case HTTP_UPDATE_OK:
      Serial.println("OTA executado com sucesso! Reiniciando...");
      desenhaTela("OTA SUCESSO", "Reiniciando modulo...", "");
      delay(1000);
      break;
  }
}

// --- CONTROLE DE PULSOS & MQTT ---
void acionarPulsos(int quantidade, const char* txId) {
  char msg[30];
  sprintf(msg, "%d Jogada(s)", quantidade);
  desenhaTela("PAGTO APROVADO", "Liberando Creditos:", msg);

  for (int i = 1; i <= quantidade; i++) {
    digitalWrite(PIN_RELE, HIGH);
    delay(100);
    digitalWrite(PIN_RELE, LOW);
    delay(100);
    totalPulsosExecutados++;
  }

  StaticJsonDocument<200> doc;
  doc["status"] = "ok";
  doc["tx_id"] = txId;
  doc["maquina_id"] = device_id;
  doc["pulsos_liberados"] = quantidade;
  doc["total_acumulado"] = totalPulsosExecutados;

  char buffer[256];
  serializeJson(doc, buffer);
  String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
  mqttClient.publish(statusTopic.c_str(), buffer);

  delay(1200);
  desenhaTela("ONLINE", "Aguardando Pix...", WiFi.localIP().toString().c_str());
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  StaticJsonDocument<256> doc;
  DeserializationError error = deserializeJson(doc, payload, length);
  if (error) return;

  const char* cmd = doc["cmd"] | "";
  int qty = doc["qty"] | 1;
  const char* txId = doc["tx_id"] | "TX-UNKNOWN";

  if (strcmp(cmd, "pulse") == 0) {
    acionarPulsos(qty, txId);
  } else if (strcmp(cmd, "ota") == 0 || strcmp(cmd, "ota_update") == 0) {
    const char* url = doc["url"] | "";
    if (strlen(url) > 0) {
      executarAtualizacaoRemotaOTA(url);
    }
  } else if (strcmp(cmd, "reset_config") == 0) {
    Serial.println(">>> RESET REMOTO VIA MQTT RECEBIDO!");
    LittleFS.remove("/config.json");
    desenhaTela("RESETANDO", "Configuracoes limpas", "Reiniciando...");
    delay(1500);
    ESP.restart();
  }
}

void reconectarMQTT() {
  while (!mqttClient.connected() && WiFi.status() == WL_CONNECTED) {
    desenhaTela("CONECTANDO", "Conectando ao Broker", mqtt_server);

    String clientId = "playbox-client-" + String(device_id);
    bool conectado = false;
    if (strlen(mqtt_user) > 0) {
      conectado = mqttClient.connect(clientId.c_str(), mqtt_user, mqtt_password);
    } else {
      conectado = mqttClient.connect(clientId.c_str());
    }

    if (conectado) {
      String cmdTopic = "playbox/maquinas/" + String(device_id) + "/comando";
      mqttClient.subscribe(cmdTopic.c_str());

      StaticJsonDocument<200> doc;
      doc["evento"] = "online";
      doc["maquina_id"] = device_id;
      doc["ip"] = WiFi.localIP().toString();
      char buf[256];
      serializeJson(doc, buf);
      String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
      mqttClient.publish(statusTopic.c_str(), buf);

      desenhaTela("ONLINE", "Aguardando Pix...", WiFi.localIP().toString().c_str());
    } else {
      Serial.printf("Falha conexao MQTT (rc=%d). Tentando em 3s...\n", mqttClient.state());
      delay(3000);
    }
  }
}

// --- SERVIDOR WEB: HTML OFICIAL COMPLETO (PORTUGUES NATIVO) ---
const char PAGE_PORTAL[] PROGMEM = R"rawhtml(
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Play Box BR - Configuração</title>
  <style>
    :root {
      --primary: #db2777;
      --primary-dark: #be185d;
      --primary-light: #fdf2f8;
      --bg: #f8fafc;
      --card: #ffffff;
      --text: #0f172a;
      --text-muted: #64748b;
      --border: #e2e8f0;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      padding: 12px;
      -webkit-font-smoothing: antialiased;
    }
    .wrap {
      max-width: 420px;
      margin: 10px auto;
      background: var(--card);
      border-radius: 20px;
      padding: 24px 20px;
      box-shadow: 0 10px 25px -5px rgba(0,0,0,0.06);
      border: 1px solid var(--border);
    }
    .pb-header {
      text-align: center;
      margin-bottom: 16px;
    }
    .pb-title {
      font-size: 24px;
      font-weight: 800;
      color: var(--primary);
      letter-spacing: -0.5px;
    }
    .pb-badge {
      display: inline-block;
      margin-top: 4px;
      font-size: 11px;
      font-weight: 700;
      color: var(--primary);
      background: var(--primary-light);
      padding: 3px 12px;
      border-radius: 9999px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    
    /* Abas sem emojis */
    .pb-tabs {
      display: flex;
      background: #f1f5f9;
      padding: 4px;
      border-radius: 12px;
      margin-bottom: 20px;
      gap: 4px;
    }
    .pb-tab {
      flex: 1;
      text-align: center;
      padding: 10px 4px;
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
      border-radius: 8px;
      cursor: pointer;
      border: none;
      background: transparent;
      transition: all 0.2s ease;
    }
    .pb-tab.active {
      background: var(--card);
      color: var(--primary);
      font-weight: 700;
      box-shadow: 0 2px 8px rgba(0,0,0,0.06);
    }
    
    .tab-content { display: none; }
    .tab-content.active { display: block; }

    /* Caixinha de Redes com Scrollbar */
    .pb-wifi-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
      padding: 0 2px;
    }
    .pb-wifi-title {
      font-size: 12px;
      font-weight: 700;
      color: #334155;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .pb-refresh-link {
      color: var(--primary);
      font-size: 13px;
      font-weight: 700;
      text-decoration: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 3px;
    }
    .pb-refresh-link:hover { text-decoration: underline; }
    
    .pb-wifi-box {
      max-height: 190px;
      overflow-y: auto;
      background: #f8fafc;
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 6px;
      margin-bottom: 16px;
      -webkit-overflow-scrolling: touch;
    }
    .pb-wifi-box::-webkit-scrollbar { width: 5px; }
    .pb-wifi-box::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 4px; }
    
    .pb-wifi-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: #ffffff;
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 10px 12px;
      margin-bottom: 5px;
      cursor: pointer;
      transition: all 0.15s;
    }
    .pb-wifi-item:hover, .pb-wifi-item:active {
      background: var(--primary-light);
      border-color: var(--primary);
    }
    .pb-wifi-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--text);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      flex: 1;
      text-align: left;
    }
    .pb-wifi-meta {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--text-muted);
      margin-left: 8px;
    }

    /* Form Fields */
    label {
      display: block;
      font-size: 13px;
      font-weight: 600;
      color: #334155;
      margin-top: 12px;
      margin-bottom: 5px;
      text-align: left;
    }
    input[type='text'], input[type='password'], input[type='number'], input[type='file'] {
      width: 100%;
      box-sizing: border-box;
      border-radius: 10px;
      border: 1px solid var(--border);
      padding: 12px 14px;
      font-size: 15px;
      background: #ffffff;
      color: var(--text);
      transition: border 0.2s, box-shadow 0.2s;
    }
    input:focus {
      outline: none;
      border-color: var(--primary);
      box-shadow: 0 0 0 3px rgba(219,39,119,0.15);
    }

    .accordion {
      margin-top: 14px;
      border: 1px solid var(--border);
      border-radius: 12px;
      overflow: hidden;
      background: #fafbfc;
    }
    .accordion-btn {
      width: 100%;
      background: #f1f5f9;
      color: #334155;
      border: none;
      padding: 12px 14px;
      font-size: 13px;
      font-weight: 700;
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
      text-align: left;
      transition: background 0.2s;
    }
    .accordion-btn:hover {
      background: #e2e8f0;
    }
    .accordion-arrow {
      font-size: 12px;
      transition: transform 0.2s ease;
      display: inline-block;
      color: #64748b;
    }
    .accordion-content {
      display: none;
      padding: 12px 14px 16px 14px;
      background: #ffffff;
      border-top: 1px solid var(--border);
    }
    .accordion-content.show {
      display: block;
    }

    .checkbox-row {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 8px;
      margin-bottom: 12px;
    }
    .checkbox-row input {
      width: 18px;
      height: 18px;
      accent-color: var(--primary);
      cursor: pointer;
    }
    .checkbox-row label {
      margin: 0;
      cursor: pointer;
      font-size: 13px;
      font-weight: 500;
      color: var(--text-muted);
    }

    .btn-primary {
      width: 100%;
      background: linear-gradient(135deg, var(--primary), var(--primary-dark));
      color: #ffffff;
      border: none;
      border-radius: 12px;
      padding: 14px 20px;
      font-size: 16px;
      font-weight: 700;
      cursor: pointer;
      margin-top: 16px;
      box-shadow: 0 4px 14px rgba(219,39,119,0.35);
      transition: transform 0.1s, opacity 0.2s;
    }
    .btn-primary:active {
      transform: scale(0.98);
      opacity: 0.95;
    }

    .btn-danger {
      width: 100%;
      background: #ef4444;
      color: #ffffff;
      border: none;
      border-radius: 12px;
      padding: 12px 16px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      margin-top: 14px;
      box-shadow: 0 4px 12px rgba(239,68,68,0.2);
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      margin-top: 10px;
    }
    td {
      padding: 9px 6px;
      border-bottom: 1px solid var(--border);
    }
    td:first-child {
      font-weight: 600;
      color: var(--text-muted);
      text-align: left;
    }
    td:last-child {
      text-align: right;
      color: var(--text);
      font-family: monospace;
    }
    
    .loading-spinner {
      text-align: center;
      padding: 24px 10px;
      color: var(--text-muted);
      font-size: 13px;
    }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="pb-header">
      <h1 class="pb-title">Play Box BR</h1>
      <span class="pb-badge">Módulo IoT Gruas</span>
    </div>

    <div class="pb-tabs">
      <button class="pb-tab active" onclick="switchTab('tab-config', this)">Configurar</button>
      <button class="pb-tab" onclick="switchTab('tab-ota', this)">Atualizar OTA</button>
      <button class="pb-tab" onclick="switchTab('tab-status', this)">Status</button>
    </div>

    <!-- ABA 1: CONFIGURAR -->
    <div id="tab-config" class="tab-content active">
      <div class="pb-wifi-header">
        <span class="pb-wifi-title">Redes Disponíveis</span>
        <a onclick="recarregarRedes()" class="pb-refresh-link" id="btn-refresh">Atualizar &#x21bb;</a>
      </div>

      <div class="pb-wifi-box" id="wifi-list">
        <div class="loading-spinner">Buscando redes Wi-Fi...</div>
      </div>

      <form action="/save" method="POST">
        <label for="ssid">Nome da Rede Wi-Fi (SSID)</label>
        <input type="text" id="ssid" name="ssid" placeholder="Selecione acima ou digite" value="%WIFI_SSID%" required maxlength="32">

        <label for="password">Senha do Wi-Fi</label>
        <input type="password" id="password" name="password" placeholder="Digite a senha da rede" value="%WIFI_PASS%" maxlength="64">

        <div class="checkbox-row">
          <input type="checkbox" id="showpass" onchange="toggleSenha()">
          <label for="showpass">Mostrar Senha</label>
        </div>

        <label for="device_id">ID do Módulo</label>
        <input type="text" id="device_id" name="device_id" value="%DEVICE_ID%" required maxlength="20">

        <!-- ACCORDION: CONFIGURAÇÕES DO BROKER MQTT -->
        <div class="accordion">
          <button type="button" class="accordion-btn" onclick="toggleAccordion('broker-acc')">
            <span>Configurações do Broker MQTT (Avançado)</span>
            <span class="accordion-arrow" id="broker-acc-arrow">&#x25b8;</span>
          </button>
          <div class="accordion-content" id="broker-acc">
            <label for="mqtt_server">Servidor Broker MQTT</label>
            <input type="text" id="mqtt_server" name="mqtt_server" value="%MQTT_SERVER%" placeholder="Ex: broker.hivemq.com ou IP" required maxlength="64">

            <label for="mqtt_port">Porta MQTT</label>
            <input type="number" id="mqtt_port" name="mqtt_port" value="%MQTT_PORT%" placeholder="1883" required min="1" max="65535">

            <label for="mqtt_user">Usuário MQTT (Opcional)</label>
            <input type="text" id="mqtt_user" name="mqtt_user" value="%MQTT_USER%" placeholder="Deixe em branco se anônimo" maxlength="32">

            <label for="mqtt_password">Senha MQTT (Opcional)</label>
            <input type="password" id="mqtt_password" name="mqtt_password" value="%MQTT_PASS%" placeholder="Deixe em branco se anônimo" maxlength="64">
          </div>
        </div>

        <button type="submit" class="btn-primary" style="margin-top:16px;">Salvar e Conectar</button>
      </form>
    </div>

    <!-- ABA 2: ATUALIZAR OTA -->
    <div id="tab-ota" class="tab-content">
      <label>Selecionar Firmware (.bin)</label>
      <form method="POST" action="/update" enctype="multipart/form-data">
        <input type="file" name="update" accept=".bin" required style="margin-top:6px;">
        <p style="font-size:12px;color:#64748b;margin-top:8px;text-align:left;">
          Selecione o arquivo binário compilado para atualizar o módulo localmente via Wi-Fi.
        </p>
        <button type="submit" class="btn-primary" style="margin-top:16px;">Iniciar Atualização (.bin)</button>
      </form>
    </div>

    <!-- ABA 3: STATUS -->
    <div id="tab-status" class="tab-content">
      <table>
        <tr><td>ID do Módulo</td><td>%DEVICE_ID%</td></tr>
        <tr><td>Endereço IP</td><td>%IP_ADDR%</td></tr>
        <tr><td>Endereço MAC</td><td>%MAC_ADDR%</td></tr>
        <tr><td>Conexão Wi-Fi</td><td>%WIFI_STATUS%</td></tr>
        <tr><td>Broker MQTT</td><td>%MQTT_SERVER%:%MQTT_PORT%</td></tr>
        <tr><td>Memória RAM Livre</td><td>%FREE_HEAP% KB</td></tr>
        <tr><td>Versão de Firmware</td><td>v2.5 - Play Box BR</td></tr>
        <tr><td>Tempo Ligado</td><td>%UPTIME%</td></tr>
      </table>
      <form action="/reset_wifi" method="POST" onsubmit="return confirm('Deseja apagar os dados de Wi-Fi e reconfigurar o módulo?');">
        <button type="submit" class="btn-danger" style="background:#f59e0b;margin-top:14px;box-shadow:0 4px 12px rgba(245,158,11,0.25);">Redefinir Wi-Fi / Reconfigurar</button>
      </form>
      <form action="/restart" method="POST">
        <button type="submit" class="btn-danger" style="margin-top:8px;">Reiniciar Módulo</button>
      </form>
    </div>
  </div>

  <script>
    function switchTab(tabId, btn) {
      document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
      document.querySelectorAll('.pb-tab').forEach(el => el.classList.remove('active'));
      document.getElementById(tabId).classList.add('active');
      btn.classList.add('active');
    }

    function toggleSenha() {
      var p = document.getElementById('password');
      p.type = (p.type === 'password') ? 'text' : 'password';
    }

    function toggleAccordion(id) {
      var c = document.getElementById(id);
      var arrow = document.getElementById(id + '-arrow');
      if (c.classList.contains('show')) {
        c.classList.remove('show');
        if (arrow) arrow.style.transform = 'rotate(0deg)';
      } else {
        c.classList.add('show');
        if (arrow) arrow.style.transform = 'rotate(90deg)';
      }
    }

    function selecionarRede(nome) {
      var s = document.getElementById('ssid');
      s.value = nome;
      document.getElementById('password').focus();
    }

    function renderizarRedes(redes) {
      var box = document.getElementById('wifi-list');
      if (!redes || redes.length === 0) {
        box.innerHTML = '<div class="loading-spinner">Nenhuma rede carregada.<br><a onclick="recarregarRedes()" style="color:#db2777;font-weight:700;display:inline-block;margin-top:6px;cursor:pointer;">Buscar redes &#x21bb;</a></div>';
        return;
      }
      var html = '';
      redes.forEach(function(net) {
        var pct = Math.min(Math.max(2 * (net.rssi + 100), 0), 100);
        html += '<div class="pb-wifi-item" onclick="selecionarRede(\'' + net.ssid.replace(/'/g, "\\'") + '\')">';
        html += '  <span class="pb-wifi-name">' + net.ssid + '</span>';
        html += '  <div class="pb-wifi-meta">';
        if (net.enc) html += '<span>🔒</span>';
        html += '    <span>' + pct + '%</span>';
        html += '  </div>';
        html += '</div>';
      });
      box.innerHTML = html;
    }

    function buscarRedes(force) {
      var refreshBtn = document.getElementById('btn-refresh');
      if (refreshBtn) refreshBtn.innerHTML = 'Buscando...';
      fetch('/api/scan' + (force ? '?force=1' : ''))
        .then(r => r.json())
        .then(data => {
          if (refreshBtn) refreshBtn.innerHTML = 'Atualizar &#x21bb;';
          if (data.status === 'scanning') {
            setTimeout(() => buscarRedes(false), 1200);
          } else {
            renderizarRedes(data.networks);
          }
        })
        .catch(err => {
          if (refreshBtn) refreshBtn.innerHTML = 'Atualizar &#x21bb;';
        });
    }

    function recarregarRedes() {
      var box = document.getElementById('wifi-list');
      box.innerHTML = '<div class="loading-spinner">Escaneando redes Wi-Fi...</div>';
      buscarRedes(true);
    }

    window.addEventListener('DOMContentLoaded', function() {
      buscarRedes(false);
    });
  </script>
</body>
</html>
)rawhtml";

// --- ROTAS DO SERVIDOR WEB ---
void handleRoot() {
  String html = FPSTR(PAGE_PORTAL);
  html.replace("%DEVICE_ID%", device_id);
  html.replace("%MQTT_SERVER%", mqtt_server);
  html.replace("%MQTT_PORT%", String(mqtt_port));
  html.replace("%MQTT_USER%", mqtt_user);
  html.replace("%MQTT_PASS%", mqtt_password);
  html.replace("%WIFI_SSID%", wifi_ssid);
  html.replace("%WIFI_PASS%", wifi_password);

  String ipStr = (WiFi.status() == WL_CONNECTED) ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
  String macStr = (WiFi.status() == WL_CONNECTED) ? WiFi.macAddress() : WiFi.softAPmacAddress();
  String wifiStatus = (WiFi.status() == WL_CONNECTED) ? (String(wifi_ssid) + " (" + String(WiFi.RSSI()) + " dBm)") : "Modo AP (PlayBox-BR)";

  html.replace("%IP_ADDR%", ipStr);
  html.replace("%MAC_ADDR%", macStr);
  html.replace("%WIFI_STATUS%", wifiStatus);
  html.replace("%FREE_HEAP%", String(ESP.getFreeHeap() / 1024.0, 1));
  html.replace("%UPTIME%", String(millis() / 1000) + "s");

  server.sendHeader("Cache-Control", "no-cache, no-store, must-revalidate");
  server.send(200, "text/html", html);
}

void handleApiScan() {
  bool force = server.hasArg("force");
  if (force) {
    triggerWifiScan(true);
    server.send(200, "application/json", "{\"status\":\"scanning\"}");
    return;
  }

  if (scanInProgress) {
    server.send(200, "application/json", "{\"status\":\"scanning\"}");
    return;
  }

  if (scanResults.size() == 0) {
    triggerWifiScan(true);
    server.send(200, "application/json", "{\"status\":\"scanning\"}");
    return;
  }

  // Gera JSON com as redes escaneadas
  String out = "{\"status\":\"ok\",\"networks\":[";
  for (size_t i = 0; i < scanResults.size(); i++) {
    if (i > 0) out += ",";
    out += "{\"ssid\":\"" + scanResults[i].ssid + "\",\"rssi\":" + String(scanResults[i].rssi) + ",\"enc\":" + (scanResults[i].encrypted ? "true" : "false") + "}";
  }
  out += "]}";

  server.sendHeader("Cache-Control", "no-cache");
  server.send(200, "application/json", out);
}

void handleSave() {
  if (server.hasArg("ssid")) {
    strlcpy(wifi_ssid, server.arg("ssid").c_str(), sizeof(wifi_ssid));
  }
  if (server.hasArg("password")) {
    String p = server.arg("password");
    p.trim();
    if (p.length() > 0) {
      strlcpy(wifi_password, p.c_str(), sizeof(wifi_password));
    }
  }
  if (server.hasArg("device_id")) {
    strlcpy(device_id, server.arg("device_id").c_str(), sizeof(device_id));
  }
  if (server.hasArg("mqtt_server")) {
    String raw = server.arg("mqtt_server");
    raw.trim();
    if (raw.startsWith("tcp://")) raw = raw.substring(6);
    if (raw.startsWith("mqtt://")) raw = raw.substring(7);

    int colon = raw.indexOf(':');
    if (colon > 0) {
      strlcpy(mqtt_server, raw.substring(0, colon).c_str(), sizeof(mqtt_server));
      int p = raw.substring(colon + 1).toInt();
      if (p > 0) mqtt_port = p;
    } else {
      strlcpy(mqtt_server, raw.c_str(), sizeof(mqtt_server));
    }
  }
  if (server.hasArg("mqtt_port")) {
    int p = server.arg("mqtt_port").toInt();
    if (p > 0) mqtt_port = p;
  }
  if (server.hasArg("mqtt_user")) {
    strlcpy(mqtt_user, server.arg("mqtt_user").c_str(), sizeof(mqtt_user));
  }
  if (server.hasArg("mqtt_password")) {
    strlcpy(mqtt_password, server.arg("mqtt_password").c_str(), sizeof(mqtt_password));
  }

  salvarParametros();

  String html = R"raw(
    <!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Salvo!</title><style>body{font-family:sans-serif;background:#f8fafc;padding:30px;text-align:center;}
    .box{background:#fff;border-radius:16px;padding:24px;max-width:380px;margin:auto;box-shadow:0 4px 12px rgba(0,0,0,0.08);border:1px solid #e2e8f0;}
    h2{color:#db2777;margin-bottom:12px;}p{color:#334155;font-size:14px;line-height:1.5;}</style></head><body>
    <div class="box"><h2>Configurações Salvas!</h2><p>O módulo está se conectando à rede <strong>)raw";
  html += wifi_ssid;
  html += R"raw(</strong>.<br><br>Acompanhe o status pela tela OLED do módulo.</p></div></body></html>)raw";

  server.send(200, "text/html", html);
  delay(1000);

  // Reinicia para aplicar nova conexao
  desenhaTela("CONECTANDO", "Conectando ao Wi-Fi:", wifi_ssid);
  ESP.restart();
}

void handleRestart() {
  server.send(200, "text/html", "<p style='text-align:center;font-family:sans-serif;padding:30px;'>Reiniciando módulo...</p>");
  delay(1000);
  ESP.restart();
}

void handleResetWifi() {
  LittleFS.remove("/config.json");
  WiFi.disconnect(true);
  server.send(200, "text/html", "<h3 style='text-align:center;color:#db2777;font-family:sans-serif;padding:30px;'>Configurações de Wi-Fi apagadas!<br>Reiniciando em modo PlayBox-BR...</h3>");
  delay(1000);
  ESP.restart();
}

void handleNotFound() {
  if (portalActive) {
    server.sendHeader("Location", "http://192.168.4.1/", true);
  } else {
    server.sendHeader("Location", "/", true);
  }
  server.sendHeader("Cache-Control", "no-cache, no-store, must-revalidate");
  server.send(302, "text/plain", "");
}

// Upload de firmware OTA via web
void handleUpdateUpload() {
  HTTPUpload& upload = server.upload();
  if (upload.status == UPLOAD_FILE_START) {
    desenhaTela("ATUALIZANDO OTA", "Recebendo firmware...", "Nao desligue!");
    Serial.setDebugOutput(true);
    WiFiUDP::stopAll();
    uint32_t maxSketchSpace = (ESP.getFreeSketchSpace() - 0x1000) & 0xFFFFF000;
    if (!Update.begin(maxSketchSpace)) {
      Update.printError(Serial);
    }
  } else if (upload.status == UPLOAD_FILE_WRITE) {
    if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
      Update.printError(Serial);
    }
  } else if (upload.status == UPLOAD_FILE_END) {
    if (Update.end(true)) {
      desenhaTela("OTA SUCESSO", "Firmware gravado!", "Reiniciando...");
    } else {
      Update.printError(Serial);
      desenhaTela("ERRO OTA", "Falha na gravacao", "");
    }
  }
  yield();
}

void handleUpdateDone() {
  server.sendHeader("Connection", "close");
  server.send(200, "text/html", Update.hasError() ? "Falha na atualizacao!" : "<h2 style='text-align:center;color:#db2777;padding:30px;font-family:sans-serif;'>Firmware Atualizado com Sucesso!<br>Reiniciando...</h2>");
  delay(1000);
  ESP.restart();
}

void configurarRotasServidor() {
  server.on("/", handleRoot);
  server.on("/api/scan", handleApiScan);
  server.on("/save", HTTP_POST, handleSave);
  server.on("/restart", HTTP_POST, handleRestart);
  server.on("/reset_wifi", HTTP_POST, handleResetWifi);
  server.on("/update", HTTP_POST, handleUpdateDone, handleUpdateUpload);

  // Probes de captive portal
  server.on("/generate_204", handleNotFound);
  server.on("/gen_204", handleNotFound);
  server.on("/blank.html", handleNotFound);
  server.on("/hotspot-detect.html", handleNotFound);
  server.on("/canonical.html", handleNotFound);
  server.on("/connecttest.txt", handleNotFound);
  server.on("/ncsi.txt", [&]() {
    server.send(200, "text/plain", "Microsoft NCSI");
  });

  server.onNotFound(handleNotFound);
}

// --- INICIALIZACAO DO MODO ACCESS POINT ---
void iniciarModoPortal() {
  portalActive = true;
  WiFi.disconnect();
  delay(100);

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP("PlayBox-BR");

  dnsServer.setErrorReplyCode(DNSReplyCode::NoError);
  dnsServer.start(53, "*", WiFi.softAPIP());

  MDNS.begin("playbox");
  MDNS.addService("http", "tcp", 80);

  configurarRotasServidor();
  server.begin();

  Serial.println(">>> MODO PORTAL ATIVADO: PlayBox-BR (192.168.4.1)");
  desenhaTela("CONFIGURACAO", "Wi-Fi: PlayBox-BR", "Acesse: playbox.setup", "ou IP: 192.168.4.1");

  // Inicia primeiro scan assincrono em background
  triggerWifiScan(true);
}

// --- SETUP PRINCIPAL ---
void setup() {
  Serial.begin(115200);
  delay(500);

  // 160MHz para maxima velocidade e fluidez
  system_update_cpu_freq(160);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);

  pinMode(PIN_RELE, OUTPUT);
  digitalWrite(PIN_RELE, LOW);

  Wire.begin(0, 2);
  display.begin(SSD1306_SWITCHCAPVCC, 0x3C);

  carregarParametros();

  desenhaTela("INICIANDO", "Iniciando sistema...", "");

  // Reset forçado para novo teste limpo solicitado pelo usuario
  if (!LittleFS.exists("/reset_v6_done")) {
    Serial.println("Reset forçado v6: Apagando config.json para novo teste...");
    LittleFS.remove("/config.json");
    wifi_ssid[0] = '\0';
    wifi_password[0] = '\0';
    File mark = LittleFS.open("/reset_v6_done", "w");
    if (mark) {
      mark.print("ok");
      mark.close();
    }
  }

  // Se nao ha Wi-Fi configurado ou foi solicitado reset, abre o portal
  if (strlen(wifi_ssid) == 0) {
    Serial.println("Sem Wi-Fi configurado. Abrindo portal PlayBox-BR...");
    iniciarModoPortal();
    return;
  }

  // Tenta conectar ao Wi-Fi configurado
  Serial.print("Conectando ao Wi-Fi: ");
  Serial.println(wifi_ssid);
  desenhaTela("CONECTANDO", "Conectando ao Wi-Fi:", wifi_ssid);

  WiFi.mode(WIFI_STA);
  WiFi.begin(wifi_ssid, wifi_password);

  unsigned long startAttempt = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 15000) {
    delay(200);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWi-Fi Conectado com sucesso!");
    desenhaTela("WIFI OK", "Conectado!", WiFi.localIP().toString().c_str());

    MDNS.begin("playbox");
    MDNS.addService("http", "tcp", 80);

    configurarRotasServidor();
    server.begin();
    Serial.printf(">>> Servidor Web ativo na rede local: http://%s ou http://playbox.local\n", WiFi.localIP().toString().c_str());

    ArduinoOTA.setHostname(device_id);
    ArduinoOTA.begin();

    mqttClient.setServer(mqtt_server, mqtt_port);
    mqttClient.setCallback(mqttCallback);
    delay(1000);
  } else {
    Serial.println("\nFalha na conexao. Abrindo portal de configuracao PlayBox-BR...");
    iniciarModoPortal();
  }
}

// --- LOOP PRINCIPAL ---
void loop() {
  if (portalActive) {
    dnsServer.processNextRequest();
    server.handleClient();
    MDNS.update();
    return;
  }

  // Servidor web ativo na rede local (responde requisicoes sem travar)
  server.handleClient();
  MDNS.update();

  // Modo Normal Conectado ao Wi-Fi e Broker MQTT
  if (WiFi.status() != WL_CONNECTED) {
    desenhaTela("WIFI QUEDA", "Tentando reconectar...", "");
    WiFi.reconnect();
    delay(2000);
    return;
  }

  if (!mqttClient.connected()) {
    reconectarMQTT();
  }

  mqttClient.loop();
  ArduinoOTA.handle();

  if (millis() - lastHeartbeat > 30000) {
    lastHeartbeat = millis();
    StaticJsonDocument<150> doc;
    doc["evento"] = "ping";
    doc["maquina_id"] = device_id;
    doc["rssi"] = WiFi.RSSI();
    doc["total_pulsos"] = totalPulsosExecutados;
    char buf[150];
    serializeJson(doc, buf);
    String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
    mqttClient.publish(statusTopic.c_str(), buf);
  }
}
