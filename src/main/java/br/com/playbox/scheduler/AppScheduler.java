package br.com.playbox.scheduler;

import br.com.playbox.service.OfflineMonitorService;
import br.com.playbox.service.ReportGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppScheduler.class);

    private final OfflineMonitorService monitorService;
    private final ReportGeneratorService reportService;
    private final br.com.playbox.service.TransactionImportService transactionImportService;
    private final br.com.playbox.service.EstoqueAlertaService estoqueAlertaService;

    @Value("${schedule.monitor.enabled:true}")
    private boolean monitorEnabled;

    @Value("${schedule.report.report-24h.enabled:true}")
    private boolean report24hEnabled;

    @Value("${schedule.report.report-12h.enabled:false}")
    private boolean report12hEnabled;

    @Value("${schedule.report.report-mes.enabled:true}")
    private boolean reportMesEnabled;

    @Value("${schedule.estoque-alerta.enabled:true}")
    private boolean estoqueAlertaEnabled;

    public AppScheduler(
            OfflineMonitorService monitorService,
            ReportGeneratorService reportService,
            br.com.playbox.service.TransactionImportService transactionImportService,
            br.com.playbox.service.EstoqueAlertaService estoqueAlertaService
    ) {
        this.monitorService = monitorService;
        this.reportService = reportService;
        this.transactionImportService = transactionImportService;
        this.estoqueAlertaService = estoqueAlertaService;
    }

    @Scheduled(cron = "${schedule.import-transactions.cron:0 30 * * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void schedulePeriodicTransactionImport() {
        try {
            java.time.LocalDate hoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
            log.info("[SCHEDULE] Executando sincronização horária de transações (ontem e hoje)...");
            transactionImportService.importDailyTransactions(hoje.minusDays(1));
            transactionImportService.importDailyTransactions(hoje);
        } catch (Exception e) {
            log.warn("[SCHEDULE] Falha na sincronização periódica de transações: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${schedule.monitor.cron:0 */5 * * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void scheduleMonitor() {
        if (!monitorEnabled) {
            log.info("Monitoramento agendado desabilitado.");
            return;
        }
        log.info("[SCHEDULE] Executando monitoramento agendado...");
        try {
            monitorService.run();
        } catch (Exception e) {
            log.error("[SCHEDULE] Erro inesperado ao executar monitoramento: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${schedule.report.report-24h.cron:0 0 0 * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void scheduleReport24h() {
        if (!report24hEnabled) {
            log.info("Relatório 24h agendado desabilitado.");
            return;
        }
        log.info("[SCHEDULE] Executando relatório 24h anterior agendado...");
        try {
            reportService.generateAndSendReport("24h-anterior");
        } catch (Exception e) {
            log.error("[SCHEDULE] Erro inesperado ao executar relatório 24h: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${schedule.report.report-12h.cron:0 0 12,0 * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void scheduleReport12h() {
        if (!report12hEnabled) {
            log.info("Relatório 12h agendado desabilitado.");
            return;
        }
        log.info("[SCHEDULE] Executando relatório 12h agendado...");
        try {
            reportService.generateAndSendReport("12h-atual");
        } catch (Exception e) {
            log.error("[SCHEDULE] Erro inesperado ao executar relatório 12h: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${schedule.report.report-mes.cron:0 0 8 * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void scheduleReportMes() {
        if (!reportMesEnabled) {
            log.info("Relatório mês agendado desabilitado.");
            return;
        }
        log.info("[SCHEDULE] Executando relatório acumulado do mês agendado...");
        try {
            reportService.generateAndSendReport("mes");
        } catch (Exception e) {
            log.error("[SCHEDULE] Erro inesperado ao executar relatório mês: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${schedule.estoque-alerta.cron:0 */15 * * * *}", zone = "${schedule.timezone:America/Sao_Paulo}")
    public void scheduleEstoqueAlerta() {
        if (!estoqueAlertaEnabled) {
            log.info("Verificação agendada de alerta de estoque desabilitada.");
            return;
        }
        log.info("[SCHEDULE] Executando verificação periódica de estoque baixo (< 30)...");
        try {
            estoqueAlertaService.verificarEEnviarAlertas();
        } catch (Exception e) {
            log.error("[SCHEDULE] Erro inesperado ao verificar alertas de estoque: {}", e.getMessage(), e);
        }
    }
}

