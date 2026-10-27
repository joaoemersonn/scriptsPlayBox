package br.com.playbox.dto;

public record ConfiguracaoRelatorioDTO(
        String whatsappReportChatId,
        String whatsappMonitorChatId,
        String whatsappEstoqueChatId,
        String whatsappColetaChatId,
        String tipoExibicao, // "GERAL" ou "DETALHADO_ESTABELECIMENTO"
        Boolean scheduleReport24hEnabled,
        String scheduleReport24hHora, // ex: "00:00"
        Boolean scheduleReport12hEnabled,
        String scheduleReport12hHora, // ex: "12:00"
        Boolean scheduleReportMesEnabled,
        String scheduleReportMesHora, // ex: "08:00"
        Boolean scheduleEstoqueAlertaEnabled,
        String scheduleEstoqueAlertaHora, // ex: "18:20"
        Boolean scheduleMonitorEnabled,
        Integer scheduleMonitorIntervalMinutes // ex: 5
) {}
