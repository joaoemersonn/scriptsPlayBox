package br.com.playbox.service;

import br.com.playbox.dto.ConfiguracaoRelatorioDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class DynamicSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchedulerService.class);
    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ConfiguracaoService configuracaoService;
    private final ReportGeneratorService reportGeneratorService;
    private final OfflineMonitorService offlineMonitorService;
    private final EstoqueAlertaService estoqueAlertaService;
    private final TransactionImportService transactionImportService;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public DynamicSchedulerService(
            ThreadPoolTaskScheduler taskScheduler,
            ConfiguracaoService configuracaoService,
            ReportGeneratorService reportGeneratorService,
            OfflineMonitorService offlineMonitorService,
            EstoqueAlertaService estoqueAlertaService,
            TransactionImportService transactionImportService
    ) {
        this.taskScheduler = taskScheduler;
        this.configuracaoService = configuracaoService;
        this.reportGeneratorService = reportGeneratorService;
        this.offlineMonitorService = offlineMonitorService;
        this.estoqueAlertaService = estoqueAlertaService;
        this.transactionImportService = transactionImportService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[DYNAMIC-SCHEDULER] Inicializando agendamentos dinâmicos a partir das configurações...");
        rescheduleAll();
    }

    public synchronized void rescheduleAll() {
        cancelAll();

        ConfiguracaoRelatorioDTO cfg = configuracaoService.getRelatorioConfig();

        // 1. Sincronização horária de transações (a cada hora no minuto 30)
        scheduleTask("importTransactions", "0 30 * * * *", () -> {
            try {
                LocalDate hoje = LocalDate.now(ZONE_SP);
                log.info("[SCHEDULE-DINÂMICO] Sincronização horária de transações...");
                transactionImportService.importDailyTransactions(hoje.minusDays(1));
                transactionImportService.importDailyTransactions(hoje);
            } catch (Exception e) {
                log.warn("[SCHEDULE-DINÂMICO] Erro na sincronização de transações: {}", e.getMessage());
            }
        });

        // 2. Monitoramento Offline
        if (Boolean.TRUE.equals(cfg.scheduleMonitorEnabled())) {
            int interval = cfg.scheduleMonitorIntervalMinutes() != null && cfg.scheduleMonitorIntervalMinutes() > 0
                    ? cfg.scheduleMonitorIntervalMinutes()
                    : 5;
            String cron = "0 */" + interval + " * * * *";
            scheduleTask("monitor", cron, () -> {
                log.info("[SCHEDULE-DINÂMICO] Executando monitoramento offline...");
                try {
                    offlineMonitorService.run();
                } catch (Exception e) {
                    log.error("[SCHEDULE-DINÂMICO] Erro ao executar monitoramento: {}", e.getMessage(), e);
                }
            });
        } else {
            log.info("[SCHEDULE-DINÂMICO] Monitoramento offline está DESABILITADO.");
        }

        // 3. Relatório 24h
        if (Boolean.TRUE.equals(cfg.scheduleReport24hEnabled())) {
            String cron = horaToCron(cfg.scheduleReport24hHora(), "00:00");
            scheduleTask("report24h", cron, () -> {
                log.info("[SCHEDULE-DINÂMICO] Executando relatório 24h anterior...");
                try {
                    reportGeneratorService.generateAndSendReport("24h-anterior");
                } catch (Exception e) {
                    log.error("[SCHEDULE-DINÂMICO] Erro no relatório 24h: {}", e.getMessage(), e);
                }
            });
        } else {
            log.info("[SCHEDULE-DINÂMICO] Relatório 24h está DESABILITADO.");
        }

        // 4. Relatório 12h
        if (Boolean.TRUE.equals(cfg.scheduleReport12hEnabled())) {
            String cron = horaToCron(cfg.scheduleReport12hHora(), "12:00");
            scheduleTask("report12h", cron, () -> {
                log.info("[SCHEDULE-DINÂMICO] Executando relatório 12h...");
                try {
                    reportGeneratorService.generateAndSendReport("12h-atual");
                } catch (Exception e) {
                    log.error("[SCHEDULE-DINÂMICO] Erro no relatório 12h: {}", e.getMessage(), e);
                }
            });
        } else {
            log.info("[SCHEDULE-DINÂMICO] Relatório 12h está DESABILITADO.");
        }

        // 5. Relatório Mês
        if (Boolean.TRUE.equals(cfg.scheduleReportMesEnabled())) {
            String cron = horaToCron(cfg.scheduleReportMesHora(), "08:00");
            scheduleTask("reportMes", cron, () -> {
                log.info("[SCHEDULE-DINÂMICO] Executando relatório acumulado do mês...");
                try {
                    reportGeneratorService.generateAndSendReport("mes");
                } catch (Exception e) {
                    log.error("[SCHEDULE-DINÂMICO] Erro no relatório do mês: {}", e.getMessage(), e);
                }
            });
        } else {
            log.info("[SCHEDULE-DINÂMICO] Relatório do mês está DESABILITADO.");
        }

        // 6. Alerta de Estoque Baixo
        if (Boolean.TRUE.equals(cfg.scheduleEstoqueAlertaEnabled())) {
            String cron = horaToCron(cfg.scheduleEstoqueAlertaHora(), "18:20");
            scheduleTask("estoqueAlerta", cron, () -> {
                log.info("[SCHEDULE-DINÂMICO] Executando verificação de alerta de estoque baixo...");
                try {
                    estoqueAlertaService.verificarEEnviarAlertas();
                } catch (Exception e) {
                    log.error("[SCHEDULE-DINÂMICO] Erro ao verificar alerta de estoque: {}", e.getMessage(), e);
                }
            });
        } else {
            log.info("[SCHEDULE-DINÂMICO] Alerta de estoque diário está DESABILITADO.");
        }

        log.info("[DYNAMIC-SCHEDULER] Todos os agendamentos foram atualizados e estão ativos sem reiniciar o container!");
    }

    private void scheduleTask(String taskKey, String cronExpression, Runnable task) {
        try {
            CronTrigger trigger = new CronTrigger(cronExpression, ZONE_SP);
            ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
            activeTasks.put(taskKey, future);
            log.info("[DYNAMIC-SCHEDULER] Tarefa '{}' agendada com cron: '{}' ({})", taskKey, cronExpression, ZONE_SP);
        } catch (Exception e) {
            log.error("[DYNAMIC-SCHEDULER] Falha ao agendar tarefa '{}' com cron '{}': {}", taskKey, cronExpression, e.getMessage());
        }
    }

    private void cancelAll() {
        activeTasks.forEach((key, future) -> {
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
                log.info("[DYNAMIC-SCHEDULER] Agendamento '{}' cancelado para reagendamento.", key);
            }
        });
        activeTasks.clear();
    }

    private String horaToCron(String horaStr, String defaultHora) {
        String horaVal = (horaStr != null && horaStr.matches("^\\d{1,2}:\\d{2}$")) ? horaStr.trim() : defaultHora;
        String[] parts = horaVal.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        return String.format("0 %d %d * * *", m, h);
    }

    @PreDestroy
    public void onDestroy() {
        cancelAll();
    }
}
