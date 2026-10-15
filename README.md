# PlayBox Scheduler & Monitor (Java 21 / Docker)

Aplicação unificada e dockerizada para monitoramento de máquinas offline e geração de relatórios de faturamento das plataformas **VendProMax**, **PayXYZ** e **Zapizi**, com alertas e relatórios enviados diretamente via **BotWhatsApp** (API REST Baileys).

O agendamento é executado internamente pela aplicação via **Spring Boot Scheduler**, eliminando a dependência do `crontab` do sistema operacional.

---

## 🚀 Arquitetura e Recursos

1. **Java 21 LTS e Spring Boot 3**:
   - Robusto, fortemente tipado e com concorrência gerenciada.
   - Chamadas HTTP nativas e serialização/desserialização JSON com Jackson.
2. **Integração WhatsApp Nativa via BotWhatsApp (Baileys REST)**:
   - Envio direto para o container `whatsapp_bot` através da rede Docker compartilhada `whatsapp-net`.
   - Porta do bot configurável via `WHATSAPP_PORT` (padrão: `3035`).
   - Autenticação por chave de API via header `x-api-key`.
3. **Agendamento Interno (Sem crontab no host)**:
   - Expressões cron configuráveis no `.env` com fuso horário `America/Sao_Paulo`.
   - Monitoramento a cada 5 minutos e relatórios automáticos (24h, 12h e mensal).
4. **Persistência de Estado Confiável**:
   - Arquivos de estado (`offline_status.txt`, `last_offline_notification.txt`, `notified_back_online.txt`) são armazenados em `/app/data`, mapeados para `./data` via volume Docker.
5. **Endpoints REST de Saúde e Disparo Manual**:
   - Healthcheck para Docker: `GET /health`
   - Disparo manual sob demanda de monitoramento: `POST /api/trigger/monitor`
   - Disparo manual sob demanda de relatórios: `POST /api/trigger/report?periodo={24h-anterior|12h-atual|mes}`

---

## ⚙️ Variáveis de Ambiente (`.env`)

A aplicação lê automaticamente o arquivo `.env` na raiz do projeto.

### Credenciais e Integrações
| Variável | Padrão | Descrição |
|---|---|---|
| `VENDPROMAX_EMAIL` | - | Email de acesso à VendProMax |
| `VENDPROMAX_PASSWORD` | - | Senha de acesso à VendProMax |
| `PAYXYZ_EMAIL` | - | Email de acesso à PayXYZ |
| `PAYXYZ_PASSWORD` | - | Senha de acesso à PayXYZ |
| `ZAPIZI_EMAIL` | - | Email de acesso à Zapizi |
| `ZAPIZI_PASSWORD` | - | Senha de acesso à Zapizi |
| `ZAPIZI_ORGANIZATION_ID` | `3852` | ID da organização na Zapizi |
| `WHATSAPP_HOST` | `whatsapp_bot` | Nome do host do container do bot WhatsApp |
| `WHATSAPP_PORT` | `3035` | Porta HTTP da API do BotWhatsApp |
| `WHATSAPP_BASE_URL` | `http://whatsapp_bot:3035` | URL base da API do BotWhatsApp |
| `WHATSAPP_API_KEY` | - | Chave de autenticação (`x-api-key`) |
| `WHATSAPP_SEND_PATH` | `/api/messages/send` | Endpoint de envio de mensagens |
| `WHATSAPP_MONITOR_CHAT_ID` | - | Chat ID para alertas de máquinas offline |
| `WHATSAPP_REPORT_CHAT_ID` | - | Chat ID para relatórios de faturamento |

### Configurações de Agendamento (Cron)
Padrão de 6 campos: `segundo minuto hora dia-do-mês mês dia-da-semana`

| Variável | Padrão | Descrição |
|---|---|---|
| `SCHEDULE_TIMEZONE` | `America/Sao_Paulo` | Fuso horário das execuções |
| `SCHEDULE_MONITOR_CRON` | `0 */5 * * * *` | Monitoramento a cada 5 minutos |
| `SCHEDULE_REPORT_24H_CRON` | `0 0 8 * * *` | Relatório 24h anterior todos os dias às 08:00 |
| `SCHEDULE_REPORT_12H_CRON` | `0 0 12,0 * * *` | Relatório 12h às 12:00 e 00:00 |
| `SCHEDULE_REPORT_MES_CRON` | `0 0 9 1 * *` | Relatório mensal no 1º dia do mês às 09:00 |

---

## 🐳 Executando com Docker

### 1. Iniciar os serviços em background:
```bash
docker compose up -d --build
```

### 2. Acompanhar os logs:
```bash
docker compose logs -f playbox-scheduler
```

### 3. Verificar o status e saúde do container:
```bash
docker compose ps
curl http://localhost:8080/health
```

### 4. Parar a aplicação:
```bash
docker compose down
```

---

## 🔄 Disparo Manual Sob Demanda

Se você quiser testar ou forçar uma execução imediatamente sem aguardar o cron:

- **Executar monitoramento agora:**
  ```bash
  curl -X POST http://localhost:8080/api/trigger/monitor
  ```

- **Executar relatório 24h anterior agora:**
  ```bash
  curl -X POST "http://localhost:8080/api/trigger/report?periodo=24h-anterior"
  ```

- **Executar relatório das últimas 12h agora:**
  ```bash
  curl -X POST "http://localhost:8080/api/trigger/report?periodo=12h-atual"
  ```

- **Executar relatório acumulado do mês agora:**
  ```bash
  curl -X POST "http://localhost:8080/api/trigger/report?periodo=mes"
  ```
