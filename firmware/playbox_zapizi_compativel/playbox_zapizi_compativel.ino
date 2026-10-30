/*
 * PLAY BOX BR - FIRMWARE HARDWARE PRÓPRIO (COMPATÍVEL CHICOTE ZAPIZI)
 * 
 * Hardware: ESP32 ou ESP8266 (Placa dedicada com conector 8 pinos Zapizi)
 * - SEM TELA OLED (Operação autônoma e silenciosa com LEDs de status)
 * - 1x Saída Optoacoplada: Injeção de Crédito Digital (Pix, Cartão, etc.) no Moedeiro (NA / Coletor Aberto)
 * - 2x Entradas Optoacopladas Isoladas para Pulsos de 12V:
 *     1. Relógio de Entrada da Grua (Odômetro de Dinheiro Físico / Fichas)
 *     2. Relógio de Saída da Grua (Odômetro de Pelúcias Ganhas / Prêmios)
 * - Servidor Web Nativo Ultrarrápido em Português (Configuração Wi-Fi + Broker via Celular)
 * - Protocolo de Telemetria Completa MQTT (Status, Pings, Eventos de Pelúcia e Espécie)
 */

#include <Arduino.h>

#if defined(ESP8266)
  #include <ESP8266WiFi.h>
  #include <ESP8266WebServer.h>
  #include <ESP8266mDNS.h>
  #include <ESP8266httpUpdate.h>
  typedef ESP8266WebServer WebServerClass;
#elif defined(ESP32)
  #include <WiFi.h>
  #include <WebServer.h>
  #include <ESPmDNS.h>
  #include <Update.h>
  #include <HTTPUpdate.h>
  typedef WebServer WebServerClass;
#endif

#include <DNSServer.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <vector>
#include <algorithm>

// --- PINAGEM DO HARDWARE COMPATÍVEL ZAPIZI ---
#if defined(ESP32)
  const int PIN_PULSO_DIGITAL    = 23; // Optoacoplador Saída -> Fio Moedeiro (Laranja)
  const int PIN_RELOGIO_ENTRADA  = 18; // Optoacoplador Entrada -> Relógio Moedas (12V)
  const int PIN_RELOGIO_SAIDA    = 19; // Optoacoplador Entrada -> Relógio Prêmios (12V)
  const int PIN_LED_WIFI         = 2;  // LED Azul (Conexão e Nuvem)
  const int PIN_LED_OPERACAO     = 4;  // LED Verde/Vermelho (Operação / Pulso)
#else
  const int PIN_PULSO_DIGITAL    = 5;  // GPIO 5 (D1)
  const int PIN_RELOGIO_ENTRADA  = 12; // GPIO 12 (D6)
  const int PIN_RELOGIO_SAIDA    = 14; // GPIO 14 (D5)
  const int PIN_LED_WIFI         = 2;  // GPIO 2
  const int PIN_LED_OPERACAO     = 4;  // GPIO 4 (D2)
#endif

// --- DADOS DE CONFIGURACAO ---
char wifi_ssid[33]     = "";
char wifi_password[65] = "";
char device_id[21]     = "PB-00001";
char mqtt_server[65]   = "broker.hivemq.com";
int  mqtt_port         = 1883;
char mqtt_user[33]     = "";
char mqtt_password[65] = "";

// --- CONTADORES ACUMULADOS (ODÔMETROS PERMANENTES) ---
volatile unsigned long totalCreditosDigitais = 0; // Créditos liberados por pagamentos digitais (Pix, Cartão, etc.)
volatile unsigned long totalRelogioEntrada   = 0; // Pulsos mecânicos de dinheiro físico / fichas
volatile unsigned long totalRelogioSaida     = 0; // Pulsos mecânicos de pelúcia ganha

// Controle de debounce das interrupções dos relógios (bobinas de 12V geram ruído)
volatile unsigned long lastDebounceEntrada = 0;
volatile unsigned long lastDebounceSaida   = 0;
const unsigned long DEBOUNCE_DELAY_MS      = 80; // 80ms mínimo entre pulsos mecânicos

// Flags para envio imediato de evento no loop principal (fora da interrupção)
volatile bool flagEventoEntrada = false;
volatile bool flagEventoSaida   = false;

// --- SERVIDORES & CLIENTES ---
WebServerClass server(80);
DNSServer dnsServer;
WiFiClient espClient;
PubSubClient mqttClient(espClient);

// --- ESTADOS DO SISTEMA ---
bool portalActive = false;
unsigned long lastHeartbeat = 0;
unsigned long lastLedBlink = 0;

// --- ESTRUTURA DE ESCANEAMENTO WI-FI ASSINCRONO ---
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
      #if defined(ESP8266)
      net.encrypted = (WiFi.encryptionType(idx) != ENC_TYPE_NONE);
      #else
      net.encrypted = (WiFi.encryptionType(idx) != WIFI_AUTH_OPEN);
      #endif
      scanResults.push_back(net);
    }
  }
  WiFi.scanDelete();
  Serial.printf(">>> Scan assincrono: %d redes encontradas.\n", (int)scanResults.size());
}

void triggerWifiScan(bool force = false) {
  if (scanInProgress) return;
  if (!force && scanResults.size() > 0 && (millis() - lastScanTime < 300000)) return;

  scanInProgress = true;
  #if defined(ESP8266)
  WiFi.scanNetworksAsync(onScanComplete);
  #else
  WiFi.scanNetworks(true); // scan assíncrono no ESP32
  #endif
}

// --- INTERRUPÇÕES DE HARDWARE PARA LEITURA DOS RELÓGIOS ---
#if defined(ESP8266)
void ICACHE_RAM_ATTR isrRelogioEntrada() {
#else
void IRAM_ATTR isrRelogioEntrada() {
#endif
  unsigned long now = millis();
  if (now - lastDebounceEntrada > DEBOUNCE_DELAY_MS) {
    totalRelogioEntrada++;
    flagEventoEntrada = true;
    lastDebounceEntrada = now;
  }
}

#if defined(ESP8266)
void ICACHE_RAM_ATTR isrRelogioSaida() {
#else
void IRAM_ATTR isrRelogioSaida() {
#endif
  unsigned long now = millis();
  if (now - lastDebounceSaida > DEBOUNCE_DELAY_MS) {
    totalRelogioSaida++;
    flagEventoSaida = true;
    lastDebounceSaida = now;
  }
}

// --- PERSISTENCIA EM MEMORIA PERMANENTE (LittleFS) ---
void carregarParametros() {
  if (!LittleFS.begin()) {
    #if defined(ESP8266)
    LittleFS.format();
    #endif
    LittleFS.begin();
  }

  // 1. Carrega Credenciais e Conexões
  if (LittleFS.exists("/config.json")) {
    File f = LittleFS.open("/config.json", "r");
    if (f) {
      StaticJsonDocument<512> doc;
      if (!deserializeJson(doc, f)) {
        strlcpy(wifi_ssid, doc["ssid"] | "", sizeof(wifi_ssid));
        strlcpy(wifi_password, doc["password"] | "", sizeof(wifi_password));
        strlcpy(device_id, doc["device_id"] | "PB-00001", sizeof(device_id));
        strlcpy(mqtt_server, doc["mqtt_server"] | "broker.hivemq.com", sizeof(mqtt_server));
        mqtt_port = doc["mqtt_port"] | 1883;
        strlcpy(mqtt_user, doc["mqtt_user"] | "", sizeof(mqtt_user));
        strlcpy(mqtt_password, doc["mqtt_password"] | "", sizeof(mqtt_password));
      }
      f.close();
    }
  }

  // 2. Carrega Odômetros Permanentes (Anti-Blecaute)
  if (LittleFS.exists("/counters.json")) {
    File f = LittleFS.open("/counters.json", "r");
    if (f) {
      StaticJsonDocument<256> doc;
      if (!deserializeJson(doc, f)) {
        totalCreditosDigitais = doc["creditos_digitais"] | doc["pulsos_pix"] | 0;
        totalRelogioEntrada   = doc["relogio_entrada"] | 0;
        totalRelogioSaida     = doc["relogio_saida"] | 0;
        Serial.printf(">>> ODOMETROS CARREGADOS: Digitais=%lu | Entrada=%lu | Pelucias=%lu\n",
          totalCreditosDigitais, totalRelogioEntrada, totalRelogioSaida);
      }
      f.close();
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
  }
}

void salvarContadoresFlash() {
  StaticJsonDocument<256> doc;
  doc["creditos_digitais"] = totalCreditosDigitais;
  doc["relogio_entrada"]   = totalRelogioEntrada;
  doc["relogio_saida"]     = totalRelogioSaida;

  File f = LittleFS.open("/counters.json", "w");
  if (f) {
    serializeJson(doc, f);
    f.close();
  }
}

// --- ATUALIZACAO OTA REMOTA ---
void executarAtualizacaoRemotaOTA(const char* url) {
  Serial.printf(">>> Iniciando OTA Remoto: %s\n", url);
  #if defined(ESP8266)
  WiFiClient updateClient;
  t_httpUpdate_return ret = ESPhttpUpdate.update(updateClient, url);
  #else
  t_httpUpdate_return ret = httpUpdate.update(espClient, url);
  #endif
}

// --- CONTROLE DE CRÉDITOS DIGITAIS & COMUNICAÇÃO MQTT ---
void acionarPulsoCredito(int quantidade, const char* txId) {
  // Pisca o LED de operação
  digitalWrite(PIN_LED_OPERACAO, HIGH);

  for (int i = 1; i <= quantidade; i++) {
    // Aciona optoacoplador para aterrar a linha do moedeiro momentaneamente (nível LOW na máquina)
    digitalWrite(PIN_PULSO_DIGITAL, HIGH);
    delay(100);
    digitalWrite(PIN_PULSO_DIGITAL, LOW);
    delay(100);
    totalCreditosDigitais++;
  }

  digitalWrite(PIN_LED_OPERACAO, LOW);
  salvarContadoresFlash();

  // Envia confirmação imediata ao broker
  StaticJsonDocument<256> doc;
  doc["status"] = "ok";
  doc["tx_id"] = txId;
  doc["maquina_id"] = device_id;
  doc["pulsos_liberados"] = quantidade;
  doc["total_digitais_acumulado"] = totalCreditosDigitais;
  doc["relogio_entrada"] = totalRelogioEntrada;
  doc["relogio_saida"] = totalRelogioSaida;

  char buffer[300];
  serializeJson(doc, buffer);
  String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
  mqttClient.publish(statusTopic.c_str(), buffer);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  StaticJsonDocument<300> doc;
  DeserializationError error = deserializeJson(doc, payload, length);
  if (error) return;

  const char* cmd = doc["cmd"] | "";
  int qty = doc["qty"] | 1;
  const char* txId = doc["tx_id"] | "TX-UNKNOWN";

  if (strcmp(cmd, "pulse") == 0) {
    acionarPulsoCredito(qty, txId);
  } else if (strcmp(cmd, "ota") == 0 || strcmp(cmd, "ota_update") == 0) {
    const char* url = doc["url"] | "";
    if (strlen(url) > 0) executarAtualizacaoRemotaOTA(url);
  } else if (strcmp(cmd, "reset_config") == 0) {
    LittleFS.remove("/config.json");
    delay(1000);
    ESP.restart();
  } else if (strcmp(cmd, "set_counters") == 0) {
    // Permite calibrar os odômetros com o número físico gravado na máquina se desejar
    if (doc.containsKey("entrada")) totalRelogioEntrada = doc["entrada"];
    if (doc.containsKey("saida"))   totalRelogioSaida   = doc["saida"];
    salvarContadoresFlash();
    Serial.println(">>> Odômetros calibrados remotamente via MQTT!");
  }
}

void reconectarMQTT() {
  while (!mqttClient.connected() && WiFi.status() == WL_CONNECTED) {
    String clientId = "playbox-zapizi-" + String(device_id);
    bool conectado = false;
    if (strlen(mqtt_user) > 0) {
      conectado = mqttClient.connect(clientId.c_str(), mqtt_user, mqtt_password);
    } else {
      conectado = mqttClient.connect(clientId.c_str());
    }

    if (conectado) {
      String cmdTopic = "playbox/maquinas/" + String(device_id) + "/comando";
      mqttClient.subscribe(cmdTopic.c_str());

      // Notifica presença online com status completo dos odômetros
      StaticJsonDocument<300> doc;
      doc["evento"] = "online";
      doc["maquina_id"] = device_id;
      doc["ip"] = WiFi.localIP().toString();
      doc["total_digitais"] = totalCreditosDigitais;
      doc["relogio_entrada"] = totalRelogioEntrada;
      doc["relogio_saida"] = totalRelogioSaida;

      char buf[300];
      serializeJson(doc, buf);
      String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
      mqttClient.publish(statusTopic.c_str(), buf);

      digitalWrite(PIN_LED_WIFI, HIGH); // LED Azul aceso fixo = Online no broker!
    } else {
      delay(3000);
    }
  }
}

// --- PÁGINA HTML OFICIAL DO PORTAL DE CONFIGURAÇÃO ---
const char PAGE_PORTAL[] PROGMEM = R"rawhtml(
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Play Box BR - Módulo Zapizi</title>
  <style>
    :root {
      --primary: #db2777;
      --primary-dark: #be185d;
      --bg: #f8fafc;
      --card-bg: #ffffff;
      --text: #0f172a;
      --text-muted: #64748b;
      --border: #e2e8f0;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 16px; min-height: 100vh; }
    .wrap { max-width: 440px; margin: 0 auto; background: var(--card-bg); border-radius: 20px; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.06), 0 8px 10px -6px rgba(0,0,0,0.04); border: 1px solid var(--border); padding: 24px 20px; }
    .pb-header { text-align: center; margin-bottom: 20px; }
    .pb-title { color: var(--primary); font-size: 24px; font-weight: 800; }
    .pb-badge { display: inline-block; background: #fdf2f8; color: var(--primary); font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 999px; margin-top: 4px; border: 1px solid #fbcfe8; }
    
    .pb-tabs { display: flex; gap: 6px; margin-bottom: 20px; border-bottom: 2px solid var(--border); padding-bottom: 8px; }
    .pb-tab { flex: 1; border: none; background: transparent; padding: 10px 4px; font-size: 13px; font-weight: 700; color: var(--text-muted); cursor: pointer; border-radius: 8px; transition: all 0.2s; text-align: center; }
    .pb-tab.active { background: #fdf2f8; color: var(--primary); }
    .tab-content { display: none; }
    .tab-content.active { display: block; }

    .pb-wifi-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
    .pb-wifi-title { font-size: 13px; font-weight: 700; color: #334155; }
    .pb-refresh-link { font-size: 12px; font-weight: 700; color: var(--primary); text-decoration: none; cursor: pointer; }
    .pb-wifi-box { max-height: 140px; overflow-y: auto; border: 1px solid var(--border); border-radius: 12px; background: #fafbfc; margin-bottom: 16px; }
    .pb-wifi-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
    .pb-wifi-item:last-child { border-bottom: none; }
    .pb-wifi-item:hover { background: #fdf2f8; }
    .pb-wifi-name { font-size: 13px; font-weight: 600; color: var(--text); }
    .pb-wifi-meta { font-size: 12px; color: var(--text-muted); display: flex; gap: 6px; }

    label { display: block; font-size: 13px; font-weight: 600; color: #334155; margin-top: 12px; margin-bottom: 5px; text-align: left; }
    input[type='text'], input[type='password'], input[type='number'], input[type='file'] {
      width: 100%; box-sizing: border-box; border-radius: 10px; border: 1px solid var(--border); padding: 12px 14px; font-size: 15px; background: #ffffff; color: var(--text); transition: border 0.2s, box-shadow 0.2s;
    }
    input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(219,39,119,0.15); }

    .accordion { margin-top: 14px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: #fafbfc; }
    .accordion-btn { width: 100%; background: #f1f5f9; color: #334155; border: none; padding: 12px 14px; font-size: 13px; font-weight: 700; display: flex; justify-content: space-between; align-items: center; cursor: pointer; text-align: left; transition: background 0.2s; }
    .accordion-btn:hover { background: #e2e8f0; }
    .accordion-arrow { font-size: 12px; transition: transform 0.2s ease; display: inline-block; color: #64748b; }
    .accordion-content { display: none; padding: 12px 14px 16px 14px; background: #ffffff; border-top: 1px solid var(--border); }
    .accordion-content.show { display: block; }

    .checkbox-row { display: flex; align-items: center; gap: 8px; margin-top: 8px; margin-bottom: 12px; }
    .checkbox-row input { width: 18px; height: 18px; accent-color: var(--primary); cursor: pointer; }
    .checkbox-row label { margin: 0; cursor: pointer; font-size: 13px; font-weight: 500; color: var(--text-muted); }

    .btn-primary { width: 100%; background: linear-gradient(135deg, var(--primary), var(--primary-dark)); color: #ffffff; border: none; border-radius: 12px; padding: 14px 20px; font-size: 16px; font-weight: 700; cursor: pointer; margin-top: 16px; box-shadow: 0 4px 14px rgba(219,39,119,0.35); }
    .btn-danger { width: 100%; background: #ef4444; color: #ffffff; border: none; border-radius: 12px; padding: 12px 16px; font-size: 14px; font-weight: 700; cursor: pointer; margin-top: 14px; box-shadow: 0 4px 12px rgba(239,68,68,0.2); }

    table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
    td { padding: 9px 6px; border-bottom: 1px solid var(--border); }
    td:first-child { font-weight: 600; color: var(--text-muted); text-align: left; }
    td:last-child { text-align: right; color: var(--text); font-family: monospace; }
    .loading-spinner { text-align: center; padding: 24px 10px; color: var(--text-muted); font-size: 13px; }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="pb-header">
      <h1 class="pb-title">Play Box BR</h1>
      <span class="pb-badge">Hardware Compatível Zapizi</span>
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
            <input type="text" id="mqtt_server" name="mqtt_server" value="%MQTT_SERVER%" placeholder="Ex: broker.hivemq.com" required maxlength="64">

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
        <tr><td>Créditos Digitais</td><td style="color:#db2777;font-weight:700;">%TOTAL_DIGITAIS%</td></tr>
        <tr><td>Relógio Entrada (Espécie)</td><td style="color:#16a34a;font-weight:700;">%TOTAL_ENTRADA%</td></tr>
        <tr><td>Relógio Saída (Pelúcias)</td><td style="color:#2563eb;font-weight:700;">%TOTAL_SAIDA%</td></tr>
        <tr><td>Memória RAM Livre</td><td>%FREE_HEAP% KB</td></tr>
        <tr><td>Versão Firmware</td><td>v3.0 - Zapizi HW</td></tr>
        <tr><td>Tempo Ligado</td><td>%UPTIME%</td></tr>
      </table>
      <form action="/reset_wifi" method="POST" onsubmit="return confirm('Deseja apagar os dados de Wi-Fi e reconfigurar o módulo?');">
        <button type="submit" class="btn-danger" style="background:#f59e0b;margin-top:14px;">Redefinir Wi-Fi / Reconfigurar</button>
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

  html.replace("%TOTAL_DIGITAIS%", String(totalCreditosDigitais));
  html.replace("%TOTAL_ENTRADA%", String(totalRelogioEntrada));
  html.replace("%TOTAL_SAIDA%", String(totalRelogioSaida));

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
  html += R"raw(</strong>.<br><br>Aguarde o LED azul acender indicando conexão com o broker.</p></div></body></html>)raw";

  server.send(200, "text/html", html);
  delay(1000);
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

void handleUpdateUpload() {
  HTTPUpload& upload = server.upload();
  if (upload.status == UPLOAD_FILE_START) {
    #if defined(ESP8266)
    WiFiUDP::stopAll();
    uint32_t maxSketchSpace = (ESP.getFreeSketchSpace() - 0x1000) & 0xFFFFF000;
    Update.begin(maxSketchSpace);
    #else
    uint32_t maxSketchSpace = UPDATE_SIZE_UNKNOWN;
    Update.begin(maxSketchSpace);
    #endif
  } else if (upload.status == UPLOAD_FILE_WRITE) {
    Update.write(upload.buf, upload.currentSize);
  } else if (upload.status == UPLOAD_FILE_END) {
    Update.end(true);
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
  triggerWifiScan(true);
}

// --- SETUP PRINCIPAL ---
void setup() {
  Serial.begin(115200);
  delay(300);

  // Configuração dos pinos de hardware
  pinMode(PIN_PULSO_DIGITAL, OUTPUT);
  digitalWrite(PIN_PULSO_DIGITAL, LOW);

  pinMode(PIN_LED_WIFI, OUTPUT);
  digitalWrite(PIN_LED_WIFI, LOW);

  pinMode(PIN_LED_OPERACAO, OUTPUT);
  digitalWrite(PIN_LED_OPERACAO, LOW);

  // Configuração das entradas dos relógios com interrupção (resistor pull-up interno)
  pinMode(PIN_RELOGIO_ENTRADA, INPUT_PULLUP);
  pinMode(PIN_RELOGIO_SAIDA, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_RELOGIO_ENTRADA), isrRelogioEntrada, FALLING);
  attachInterrupt(digitalPinToInterrupt(PIN_RELOGIO_SAIDA), isrRelogioSaida, FALLING);

  carregarParametros();

  if (strlen(wifi_ssid) == 0) {
    iniciarModoPortal();
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(wifi_ssid, wifi_password);

  unsigned long startAttempt = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 15000) {
    delay(200);
    // Pisca LED enquanto conecta
    digitalWrite(PIN_LED_WIFI, !digitalRead(PIN_LED_WIFI));
  }

  if (WiFi.status() == WL_CONNECTED) {
    digitalWrite(PIN_LED_WIFI, HIGH);
    MDNS.begin("playbox");
    MDNS.addService("http", "tcp", 80);

    configurarRotasServidor();
    server.begin();

    mqttClient.setServer(mqtt_server, mqtt_port);
    mqttClient.setCallback(mqttCallback);
  } else {
    iniciarModoPortal();
  }
}

// --- LOOP PRINCIPAL ---
void loop() {
  if (portalActive) {
    dnsServer.processNextRequest();
    server.handleClient();
    #if defined(ESP8266)
    MDNS.update();
    #endif
    return;
  }

  server.handleClient();
  #if defined(ESP8266)
  MDNS.update();
  #endif

  if (WiFi.status() != WL_CONNECTED) {
    digitalWrite(PIN_LED_WIFI, LOW);
    WiFi.reconnect();
    delay(2000);
    return;
  }

  if (!mqttClient.connected()) {
    reconectarMQTT();
  }

  mqttClient.loop();

  // --- TRATAMENTO DOS EVENTOS INSTANTÂNEOS DE RELÓGIO (FORA DA ISR) ---
  if (flagEventoEntrada) {
    flagEventoEntrada = false;
    salvarContadoresFlash();

    StaticJsonDocument<256> doc;
    doc["evento"] = "relogio_entrada";
    doc["maquina_id"] = device_id;
    doc["tipo"] = "especie";
    doc["total_relogio_entrada"] = totalRelogioEntrada;
    doc["relogio_saida"] = totalRelogioSaida;

    char buf[256];
    serializeJson(doc, buf);
    String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
    mqttClient.publish(statusTopic.c_str(), buf);
    Serial.printf(">>> RELOGIO ENTRADA (DINHEIRO): Total=%lu\n", totalRelogioEntrada);
  }

  if (flagEventoSaida) {
    flagEventoSaida = false;
    salvarContadoresFlash();

    StaticJsonDocument<256> doc;
    doc["evento"] = "relogio_saida";
    doc["maquina_id"] = device_id;
    doc["tipo"] = "pelucia_ganha";
    doc["total_relogio_saida"] = totalRelogioSaida;
    doc["relogio_entrada"] = totalRelogioEntrada;

    char buf[256];
    serializeJson(doc, buf);
    String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
    mqttClient.publish(statusTopic.c_str(), buf);
    Serial.printf(">>> RELOGIO SAIDA (PELUCIA GANHA!): Total=%lu\n", totalRelogioSaida);
  }

  // --- TELEMETRIA PERIÓDICA (HEARTBEAT A CADA 30S) ---
  if (millis() - lastHeartbeat > 30000) {
    lastHeartbeat = millis();

    StaticJsonDocument<300> doc;
    doc["evento"] = "ping";
    doc["maquina_id"] = device_id;
    doc["rssi"] = WiFi.RSSI();
    doc["total_digitais"] = totalCreditosDigitais;
    doc["relogio_entrada"] = totalRelogioEntrada;
    doc["relogio_saida"] = totalRelogioSaida;
    doc["uptime"] = millis() / 1000;

    char buf[300];
    serializeJson(doc, buf);
    String statusTopic = "playbox/maquinas/" + String(device_id) + "/status";
    mqttClient.publish(statusTopic.c_str(), buf);
  }
}
