package br.com.playbox.runner;

import br.com.playbox.service.OfflineMonitorService;
import br.com.playbox.service.ReportGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class AppCommandLineRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AppCommandLineRunner.class);

    private final OfflineMonitorService monitorService;
    private final ReportGeneratorService reportService;
    private final ApplicationContext applicationContext;

    public AppCommandLineRunner(
            OfflineMonitorService monitorService,
            ReportGeneratorService reportService,
            ApplicationContext applicationContext
    ) {
        this.monitorService = monitorService;
        this.reportService = reportService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        if (args == null || args.length == 0) {
            log.info("Nenhum comando CLI fornecido. Iniciando em modo daemon/agendador...");
            return;
        }

        log.info("Argumentos CLI recebidos: {}", Arrays.toString(args));
        String command = args[0].toLowerCase();

        if ("monitor".equals(command)) {
            log.info("Executando monitoramento via CLI...");
            monitorService.run();
            exitApplication(0);
        } else if ("report".equals(command)) {
            String periodo = "24h-anterior";
            for (int i = 1; i < args.length; i++) {
                if ("--periodo".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                    periodo = args[i + 1];
                    break;
                }
            }
            log.info("Executando relatório via CLI (período: {})...", periodo);
            reportService.generateAndSendReport(periodo);
            exitApplication(0);
        }
    }

    private void exitApplication(int exitCode) {
        int code = SpringApplication.exit(applicationContext, () -> exitCode);
        System.exit(code);
    }
}

