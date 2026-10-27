package br.com.playbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agendador substituído por {@link br.com.playbox.service.DynamicSchedulerService}.
 * O DynamicSchedulerService permite alterar horários e ativar/desativar rotinas
 * em tempo real pela interface sem precisar reiniciar a aplicação.
 */
@Component
public class AppScheduler {
    private static final Logger log = LoggerFactory.getLogger(AppScheduler.class);

    public AppScheduler() {
        log.info("[APP-SCHEDULER] Agendamentos gerenciados dinamicamente via DynamicSchedulerService.");
    }
}
