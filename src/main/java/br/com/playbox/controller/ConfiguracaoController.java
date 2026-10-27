package br.com.playbox.controller;

import br.com.playbox.client.WhatsAppSender;
import br.com.playbox.config.AppProperties;
import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.dto.ConfiguracaoRelatorioDTO;
import br.com.playbox.service.ConfiguracaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/configuracoes")
@Tag(name = "Configurações", description = "Endpoints para configuração dinâmica de relatórios, agendamentos e WhatsApp sem reiniciar")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class ConfiguracaoController {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoController.class);

    private final ConfiguracaoService configuracaoService;
    private final AppProperties properties;

    public ConfiguracaoController(ConfiguracaoService configuracaoService, AppProperties properties) {
        this.configuracaoService = configuracaoService;
        this.properties = properties;
    }

    @GetMapping("/relatorio")
    @Operation(summary = "Obter configurações atuais de relatórios, WhatsApp e agendamentos")
    public ResponseEntity<ConfiguracaoRelatorioDTO> getRelatorioConfig() {
        return ResponseEntity.ok(configuracaoService.getRelatorioConfig());
    }

    @PutMapping("/relatorio")
    @Operation(summary = "Salvar novas configurações e aplicar reagendamentos imediatamente sem reiniciar")
    public ResponseEntity<ConfiguracaoRelatorioDTO> salvarRelatorioConfig(@RequestBody ConfiguracaoRelatorioDTO dto) {
        ConfiguracaoRelatorioDTO salvo = configuracaoService.salvarRelatorioConfig(dto);
        return ResponseEntity.ok(salvo);
    }

    @PostMapping("/relatorio/testar-envio")
    @Operation(summary = "Disparar mensagem teste no WhatsApp para validar entrega no grupo configurado")
    public ResponseEntity<Map<String, Object>> testarEnvioWhatsApp(@RequestParam(required = false) String tipo) {
        try {
            String targetChatId;
            String nomeDestino;

            if ("monitor".equalsIgnoreCase(tipo)) {
                targetChatId = configuracaoService.getMonitorChatId();
                nomeDestino = "Monitor Offline";
            } else if ("estoque".equalsIgnoreCase(tipo)) {
                targetChatId = configuracaoService.getEstoqueChatId();
                nomeDestino = "Alerta de Estoque";
            } else if ("coleta".equalsIgnoreCase(tipo)) {
                targetChatId = configuracaoService.getColetaChatId();
                nomeDestino = "Coleta";
            } else {
                targetChatId = configuracaoService.getReportChatId();
                nomeDestino = "Relatório de Faturamento";
            }

            if (targetChatId == null || targetChatId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "O Chat ID do WhatsApp para " + nomeDestino + " não está configurado."
                ));
            }

            AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
            String baseUrl = wa != null && wa.getBaseUrl() != null ? wa.getBaseUrl() : "http://whatsapp_bot:3035";
            String sendPath = wa != null && wa.getSendPath() != null ? wa.getSendPath() : "/api/messages/send";
            String apiKey = wa != null && wa.getApiKey() != null ? wa.getApiKey() : "";

            WhatsAppSender sender = new WhatsAppSender(
                    baseUrl,
                    apiKey,
                    targetChatId,
                    sendPath,
                    properties != null ? properties.getTimeoutSeconds() : 30,
                    null
            );

            String agora = LocalDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

            String msg = "🤖 *TESTE DE INTEGRAÇÃO PLAYBOX*\n\n" +
                    "✅ Mensagem de validação enviada com sucesso para *" + nomeDestino + "*.\n" +
                    "🕒 *Data/Hora:* " + agora + "\n" +
                    "🎯 *Destino:* " + targetChatId + "\n\n" +
                    "_Seu canal de notificações WhatsApp está funcionando normalmente!_";

            boolean ok = sender.sendMessage(targetChatId, msg);

            if (ok) {
                return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "message", "Mensagem de teste enviada com sucesso para " + nomeDestino + " (" + targetChatId + ")!"
                ));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "status", "ERROR",
                        "message", "Falha ao enviar mensagem via WhatsApp Bot. Verifique se o container whatsapp-bot está ativo e o Chat ID está correto."
                ));
            }
        } catch (Exception e) {
            log.error("Erro ao testar envio WhatsApp: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", "Erro ao disparar teste: " + e.getMessage()
            ));
        }
    }
}
