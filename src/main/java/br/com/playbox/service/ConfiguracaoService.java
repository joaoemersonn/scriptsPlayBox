package br.com.playbox.service;

import br.com.playbox.config.AppProperties;
import br.com.playbox.dto.ConfiguracaoRelatorioDTO;
import br.com.playbox.entity.ConfiguracaoSistema;
import br.com.playbox.repository.ConfiguracaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ConfiguracaoService {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoService.class);

    // Chaves de configuração
    public static final String KEY_WA_REPORT_CHAT_ID = "whatsapp.report.chat_id";
    public static final String KEY_WA_MONITOR_CHAT_ID = "whatsapp.monitor.chat_id";
    public static final String KEY_WA_ESTOQUE_CHAT_ID = "whatsapp.estoque.chat_id";
    public static final String KEY_WA_COLETA_CHAT_ID = "whatsapp.coleta.chat_id";
    public static final String KEY_RELATORIO_TIPO_EXIBICAO = "relatorio.tipo_exibicao"; // "GERAL" ou "DETALHADO_ESTABELECIMENTO"

    public static final String KEY_SCHED_24H_ENABLED = "schedule.report_24h.enabled";
    public static final String KEY_SCHED_24H_HORA = "schedule.report_24h.hora";
    public static final String KEY_SCHED_12H_ENABLED = "schedule.report_12h.enabled";
    public static final String KEY_SCHED_12H_HORA = "schedule.report_12h.hora";
    public static final String KEY_SCHED_MES_ENABLED = "schedule.report_mes.enabled";
    public static final String KEY_SCHED_MES_HORA = "schedule.report_mes.hora";
    public static final String KEY_SCHED_ESTOQUE_ENABLED = "schedule.estoque_alerta.enabled";
    public static final String KEY_SCHED_ESTOQUE_HORA = "schedule.estoque_alerta.hora";
    public static final String KEY_SCHED_MONITOR_ENABLED = "schedule.monitor.enabled";
    public static final String KEY_SCHED_MONITOR_INTERVAL = "schedule.monitor.interval_minutes";

    private final ConfiguracaoRepository repository;
    private final AppProperties properties;
    private final DynamicSchedulerService dynamicSchedulerService;

    public ConfiguracaoService(
            ConfiguracaoRepository repository,
            AppProperties properties,
            @Lazy DynamicSchedulerService dynamicSchedulerService
    ) {
        this.repository = repository;
        this.properties = properties;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    public String get(String chave, String defaultValue) {
        try {
            Optional<ConfiguracaoSistema> opt = repository.findById(chave);
            if (opt.isPresent() && opt.get().getValor() != null) {
                return opt.get().getValor();
            }
        } catch (Exception e) {
            log.warn("Erro ao ler configuração '{}' do banco: {}", chave, e.getMessage());
        }
        return defaultValue;
    }

    public boolean getBoolean(String chave, boolean defaultValue) {
        String val = get(chave, null);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    public int getInt(String chave, int defaultValue) {
        String val = get(chave, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void set(String chave, String valor, String descricao) {
        ConfiguracaoSistema conf = repository.findById(chave)
                .orElse(new ConfiguracaoSistema(chave, valor, descricao));
        conf.setValor(valor);
        if (descricao != null) conf.setDescricao(descricao);
        repository.save(conf);
    }

    public ConfiguracaoRelatorioDTO getRelatorioConfig() {
        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;

        String defReportChat = wa != null && wa.getReportChatId() != null ? wa.getReportChatId() : "";
        String defMonitorChat = wa != null && wa.getMonitorChatId() != null ? wa.getMonitorChatId() : "";
        String defEstoqueChat = wa != null && wa.getEstoqueChatId() != null ? wa.getEstoqueChatId() : defReportChat;
        String defColetaChat = wa != null && wa.getColetaChatId() != null ? wa.getColetaChatId() : defReportChat;

        return new ConfiguracaoRelatorioDTO(
                get(KEY_WA_REPORT_CHAT_ID, defReportChat),
                get(KEY_WA_MONITOR_CHAT_ID, defMonitorChat),
                get(KEY_WA_ESTOQUE_CHAT_ID, defEstoqueChat),
                get(KEY_WA_COLETA_CHAT_ID, defColetaChat),
                get(KEY_RELATORIO_TIPO_EXIBICAO, "DETALHADO_ESTABELECIMENTO"),
                getBoolean(KEY_SCHED_24H_ENABLED, true),
                get(KEY_SCHED_24H_HORA, "00:00"),
                getBoolean(KEY_SCHED_12H_ENABLED, false),
                get(KEY_SCHED_12H_HORA, "12:00"),
                getBoolean(KEY_SCHED_MES_ENABLED, true),
                get(KEY_SCHED_MES_HORA, "08:00"),
                getBoolean(KEY_SCHED_ESTOQUE_ENABLED, true),
                get(KEY_SCHED_ESTOQUE_HORA, "18:20"),
                getBoolean(KEY_SCHED_MONITOR_ENABLED, true),
                getInt(KEY_SCHED_MONITOR_INTERVAL, 5)
        );
    }

    @Transactional
    public ConfiguracaoRelatorioDTO salvarRelatorioConfig(ConfiguracaoRelatorioDTO dto) {
        if (dto == null) return getRelatorioConfig();

        if (dto.whatsappReportChatId() != null) {
            set(KEY_WA_REPORT_CHAT_ID, dto.whatsappReportChatId().trim(), "Chat ID WhatsApp Relatórios");
        }
        if (dto.whatsappMonitorChatId() != null) {
            set(KEY_WA_MONITOR_CHAT_ID, dto.whatsappMonitorChatId().trim(), "Chat ID WhatsApp Monitor Offline");
        }
        if (dto.whatsappEstoqueChatId() != null) {
            set(KEY_WA_ESTOQUE_CHAT_ID, dto.whatsappEstoqueChatId().trim(), "Chat ID WhatsApp Alerta Estoque");
        }
        if (dto.whatsappColetaChatId() != null) {
            set(KEY_WA_COLETA_CHAT_ID, dto.whatsappColetaChatId().trim(), "Chat ID WhatsApp Coleta");
        }
        if (dto.tipoExibicao() != null && !dto.tipoExibicao().isBlank()) {
            set(KEY_RELATORIO_TIPO_EXIBICAO, dto.tipoExibicao().trim().toUpperCase(), "Tipo de Exibição do Relatório (GERAL ou DETALHADO_ESTABELECIMENTO)");
        }

        if (dto.scheduleReport24hEnabled() != null) {
            set(KEY_SCHED_24H_ENABLED, String.valueOf(dto.scheduleReport24hEnabled()), "Agendamento Relatório 24h Habilitado");
        }
        if (dto.scheduleReport24hHora() != null && !dto.scheduleReport24hHora().isBlank()) {
            set(KEY_SCHED_24H_HORA, dto.scheduleReport24hHora().trim(), "Horário Relatório 24h (HH:mm)");
        }

        if (dto.scheduleReport12hEnabled() != null) {
            set(KEY_SCHED_12H_ENABLED, String.valueOf(dto.scheduleReport12hEnabled()), "Agendamento Relatório 12h Habilitado");
        }
        if (dto.scheduleReport12hHora() != null && !dto.scheduleReport12hHora().isBlank()) {
            set(KEY_SCHED_12H_HORA, dto.scheduleReport12hHora().trim(), "Horário Relatório 12h (HH:mm)");
        }

        if (dto.scheduleReportMesEnabled() != null) {
            set(KEY_SCHED_MES_ENABLED, String.valueOf(dto.scheduleReportMesEnabled()), "Agendamento Relatório Mês Habilitado");
        }
        if (dto.scheduleReportMesHora() != null && !dto.scheduleReportMesHora().isBlank()) {
            set(KEY_SCHED_MES_HORA, dto.scheduleReportMesHora().trim(), "Horário Relatório Mês (HH:mm)");
        }

        if (dto.scheduleEstoqueAlertaEnabled() != null) {
            set(KEY_SCHED_ESTOQUE_ENABLED, String.valueOf(dto.scheduleEstoqueAlertaEnabled()), "Agendamento Alerta Estoque Habilitado");
        }
        if (dto.scheduleEstoqueAlertaHora() != null && !dto.scheduleEstoqueAlertaHora().isBlank()) {
            set(KEY_SCHED_ESTOQUE_HORA, dto.scheduleEstoqueAlertaHora().trim(), "Horário Alerta Estoque (HH:mm)");
        }

        if (dto.scheduleMonitorEnabled() != null) {
            set(KEY_SCHED_MONITOR_ENABLED, String.valueOf(dto.scheduleMonitorEnabled()), "Agendamento Monitor Offline Habilitado");
        }
        if (dto.scheduleMonitorIntervalMinutes() != null && dto.scheduleMonitorIntervalMinutes() > 0) {
            set(KEY_SCHED_MONITOR_INTERVAL, String.valueOf(dto.scheduleMonitorIntervalMinutes()), "Intervalo do Monitor Offline em minutos");
        }

        log.info("[CONFIG] Configurações de relatório e agendamentos atualizadas. Aplicando reagendamento dinâmico sem reiniciar...");
        try {
            if (dynamicSchedulerService != null) {
                dynamicSchedulerService.rescheduleAll();
            }
        } catch (Exception e) {
            log.error("[CONFIG] Erro ao reagendar tarefas dinamicamente: {}", e.getMessage(), e);
        }

        return getRelatorioConfig();
    }

    public String getReportChatId() {
        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
        String def = wa != null && wa.getReportChatId() != null ? wa.getReportChatId() : "";
        return get(KEY_WA_REPORT_CHAT_ID, def);
    }

    public String getMonitorChatId() {
        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
        String def = wa != null && wa.getMonitorChatId() != null ? wa.getMonitorChatId() : "";
        return get(KEY_WA_MONITOR_CHAT_ID, def);
    }

    public String getEstoqueChatId() {
        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
        String def = wa != null && wa.getEstoqueChatId() != null ? wa.getEstoqueChatId() : getReportChatId();
        return get(KEY_WA_ESTOQUE_CHAT_ID, def);
    }

    public String getColetaChatId() {
        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
        String def = wa != null && wa.getColetaChatId() != null ? wa.getColetaChatId() : getReportChatId();
        return get(KEY_WA_COLETA_CHAT_ID, def);
    }

    public String getTipoExibicaoRelatorio() {
        return get(KEY_RELATORIO_TIPO_EXIBICAO, "DETALHADO_ESTABELECIMENTO");
    }
}
