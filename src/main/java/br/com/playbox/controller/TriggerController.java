package br.com.playbox.controller;

import br.com.playbox.service.OfflineMonitorService;
import br.com.playbox.service.ReportGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final OfflineMonitorService monitorService;
    private final ReportGeneratorService reportService;

    public TriggerController(OfflineMonitorService monitorService, ReportGeneratorService reportService) {
        this.monitorService = monitorService;
        this.reportService = reportService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "playbox-scheduler"
        ));
    }

    @PostMapping("/api/trigger/monitor")
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

    @PostMapping("/api/trigger/report")
    public ResponseEntity<Map<String, String>> triggerReport(
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
