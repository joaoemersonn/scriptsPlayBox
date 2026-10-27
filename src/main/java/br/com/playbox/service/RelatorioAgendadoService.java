package br.com.playbox.service;

import br.com.playbox.entity.RelatorioAgendado;
import br.com.playbox.repository.RelatorioAgendadoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RelatorioAgendadoService {

    private static final Logger log = LoggerFactory.getLogger(RelatorioAgendadoService.class);

    private final RelatorioAgendadoRepository repository;
    private final ReportGeneratorService reportGeneratorService;
    private final EstoqueAlertaService estoqueAlertaService;
    private final DynamicSchedulerService dynamicSchedulerService;

    public RelatorioAgendadoService(
            RelatorioAgendadoRepository repository,
            ReportGeneratorService reportGeneratorService,
            EstoqueAlertaService estoqueAlertaService,
            @Lazy DynamicSchedulerService dynamicSchedulerService
    ) {
        this.repository = repository;
        this.reportGeneratorService = reportGeneratorService;
        this.estoqueAlertaService = estoqueAlertaService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    public List<RelatorioAgendado> listarTodos() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<RelatorioAgendado> listarAtivos() {
        return repository.findByAtivoTrue();
    }

    public Optional<RelatorioAgendado> buscarPorId(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public RelatorioAgendado salvar(RelatorioAgendado agendamento) {
        if (agendamento.getAtivo() == null) {
            agendamento.setAtivo(true);
        }
        if (agendamento.getTipoExibicao() == null || agendamento.getTipoExibicao().isBlank()) {
            agendamento.setTipoExibicao("DETALHADO_ESTABELECIMENTO");
        }
        agendamento.recalcularCron();
        RelatorioAgendado salvo = repository.save(agendamento);
        log.info("[RELATORIO-AGENDADO] Salvo com sucesso id={} nome='{}' cron='{}'", salvo.getId(), salvo.getNome(), salvo.getCronExpression());

        if (dynamicSchedulerService != null) {
            dynamicSchedulerService.rescheduleRelatoriosAgendados();
        }
        return salvo;
    }

    @Transactional
    public RelatorioAgendado toggleAtivo(Long id) {
        RelatorioAgendado relatorio = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relatório agendado não encontrado com ID: " + id));
        relatorio.setAtivo(!Boolean.TRUE.equals(relatorio.getAtivo()));
        RelatorioAgendado salvo = repository.save(relatorio);
        log.info("[RELATORIO-AGENDADO] Status de ativo alterado para {} no relatório id={}", salvo.getAtivo(), id);

        if (dynamicSchedulerService != null) {
            dynamicSchedulerService.rescheduleRelatoriosAgendados();
        }
        return salvo;
    }

    @Transactional
    public void excluir(Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            log.info("[RELATORIO-AGENDADO] Relatório id={} excluído.", id);
            if (dynamicSchedulerService != null) {
                dynamicSchedulerService.rescheduleRelatoriosAgendados();
            }
        }
    }

    @Transactional
    public boolean executarDisparo(Long id) {
        RelatorioAgendado relatorio = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relatório agendado não encontrado com ID: " + id));
        return executarDisparo(relatorio);
    }

    @Transactional
    public boolean executarDisparo(RelatorioAgendado relatorio) {
        if (relatorio == null) return false;

        log.info("[RELATORIO-AGENDADO] Iniciando disparo do relatório id={} ('{}') tipo={} modo={} destino={}",
                relatorio.getId(), relatorio.getNome(), relatorio.getTipo(), relatorio.getTipoExibicao(), relatorio.getWhatsappChatId());

        boolean sucesso = false;
        String mensagemStatus = "";

        try {
            String tipo = relatorio.getTipo() != null ? relatorio.getTipo().toUpperCase() : "FATURAMENTO_24H";
            String exibicao = relatorio.getTipoExibicao();
            String chatId = relatorio.getWhatsappChatId();

            switch (tipo) {
                case "FATURAMENTO_24H" -> {
                    sucesso = reportGeneratorService.generateAndSendReport("24h-anterior", exibicao, chatId);
                    mensagemStatus = sucesso ? "Relatório 24h enviado com sucesso" : "Falha ao enviar relatório 24h";
                }
                case "FATURAMENTO_12H" -> {
                    sucesso = reportGeneratorService.generateAndSendReport("12h-atual", exibicao, chatId);
                    mensagemStatus = sucesso ? "Relatório 12h enviado com sucesso" : "Falha ao enviar relatório 12h";
                }
                case "FATURAMENTO_MES" -> {
                    sucesso = reportGeneratorService.generateAndSendReport("mes", exibicao, chatId);
                    mensagemStatus = sucesso ? "Relatório do Mês enviado com sucesso" : "Falha ao enviar relatório do mês";
                }
                case "FATURAMENTO_HOJE" -> {
                    sucesso = reportGeneratorService.generateAndSendReport("hoje", exibicao, chatId);
                    mensagemStatus = sucesso ? "Relatório de Hoje enviado com sucesso" : "Falha ao enviar relatório de hoje";
                }
                case "ALERTA_ESTOQUE" -> {
                    int alertasEnviados = estoqueAlertaService.verificarEEnviarAlertas(chatId);
                    sucesso = true;
                    mensagemStatus = "Verificação de estoque concluída (" + alertasEnviados + " lojas críticas alertadas)";
                }
                default -> {
                    sucesso = reportGeneratorService.generateAndSendReport("24h-anterior", exibicao, chatId);
                    mensagemStatus = "Relatório executado com tipo padrão 24h";
                }
            }
        } catch (Exception e) {
            sucesso = false;
            mensagemStatus = "Erro na execução: " + e.getMessage();
            log.error("[RELATORIO-AGENDADO] Erro ao disparar relatório id={}: {}", relatorio.getId(), e.getMessage(), e);
        }

        relatorio.setUltimoDisparo(LocalDateTime.now());
        relatorio.setUltimoStatus(sucesso ? "SUCCESS" : "ERROR");
        relatorio.setUltimaMensagem(mensagemStatus);
        repository.save(relatorio);

        return sucesso;
    }
}
