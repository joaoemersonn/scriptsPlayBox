package br.com.playbox.controller;

import br.com.playbox.service.OfflineMonitorService;
import br.com.playbox.service.ReportGeneratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TriggerController {

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
        monitorService.run();
        return ResponseEntity.ok(Map.of("message", "Monitoramento executado com sucesso"));
    }

    @PostMapping("/api/trigger/report")
    public ResponseEntity<Map<String, String>> triggerReport(
            @RequestParam(defaultValue = "24h-anterior") String periodo
    ) {
        reportService.generateAndSendReport(periodo);
        return ResponseEntity.ok(Map.of(
                "message", "Relatório disparado com sucesso",
                "periodo", periodo
        ));
    }
}

