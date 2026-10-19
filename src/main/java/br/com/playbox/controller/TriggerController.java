package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.service.OfflineMonitorService;
import br.com.playbox.service.ReportGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "Operações & Monitoramento", description = "Endpoints para verificação de saúde e disparo manual sob demanda de rotinas")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final OfflineMonitorService monitorService;
    private final ReportGeneratorService reportService;
    private final br.com.playbox.service.TransactionImportService transactionImportService;

    public TriggerController(
            OfflineMonitorService monitorService,
            ReportGeneratorService reportService,
            br.com.playbox.service.TransactionImportService transactionImportService
    ) {
        this.monitorService = monitorService;
        this.reportService = reportService;
        this.transactionImportService = transactionImportService;
    }

    @GetMapping("/health")
    @Operation(summary = "Verificar integridade do serviço (Healthcheck)", description = "Endpoint público para checagem de integridade e liveness pelo Docker.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Serviço ativo e operacional")
    })
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "playbox-scheduler"
        ));
    }

    @PostMapping("/api/trigger/monitor")
    @Operation(
            summary = "Disparar monitoramento de máquinas offline",
            description = "Executa imediatamente a consulta de máquinas nas plataformas VendProMax, PayXYZ e Zapizi, e envia alerta via WhatsApp se houver mudanças.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Monitoramento executado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Não autorizado (X-API-KEY ausente ou inválida)"),
            @ApiResponse(responseCode = "500", description = "Falha interna ao executar monitoramento")
    })
    public ResponseEntity<Map<String, String>> triggerMonitor() {
        try {
            monitorService.run();
            return ResponseEntity.ok(Map.of("message", "Monitoramento executado com sucesso"));
        } catch (Exception e) {
            log.error("Erro ao executar trigger monitor: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"
            ));
        }
    }

    @PostMapping("/api/trigger/import-transactions")
    @Operation(
            summary = "Disparar importação manual de transações",
            description = "Importa e sincroniza as transações de todas as máquinas para a data especificada (padrão: ontem).",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<Map<String, Object>> triggerImport(
            @Parameter(description = "Data no formato YYYY-MM-DD (ex: 2026-10-18). Padrão: ontem", example = "2026-10-18")
            @RequestParam(required = false) String data
    ) {
        try {
            java.time.LocalDate targetDate = (data != null && !data.isBlank())
                    ? java.time.LocalDate.parse(data.trim())
                    : java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusDays(1);

            int imported = transactionImportService.importDailyTransactions(targetDate);
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "data", targetDate.toString(),
                    "registrosImportados", imported
            ));
        } catch (Exception e) {
            log.error("Erro ao importar transações manualmente: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"
            ));
        }
    }

    @PostMapping("/api/trigger/report")
    @Operation(
            summary = "Disparar geração e envio de relatório de faturamento",
            description = "Gera o relatório consolidado de vendas, estornos e prêmios para o período especificado e o envia no WhatsApp.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Relatório disparado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Não autorizado (X-API-KEY ausente ou inválida)"),
            @ApiResponse(responseCode = "500", description = "Falha interna ao gerar relatório")
    })
    public ResponseEntity<Map<String, String>> triggerReport(
            @Parameter(description = "Período desejado: '24h-anterior', '12h-atual' ou 'mes'", example = "24h-anterior")
            @RequestParam(defaultValue = "24h-anterior") String periodo
    ) {
        try {
            reportService.generateAndSendReport(periodo);
            return ResponseEntity.ok(Map.of(
                    "message", "Relatório disparado com sucesso",
                    "periodo", periodo
            ));
        } catch (Exception e) {
            log.error("Erro ao executar trigger report ({}): {}", periodo, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "periodo", periodo,
                    "message", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"
            ));
        }
    }
}
