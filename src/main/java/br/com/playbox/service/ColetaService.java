package br.com.playbox.service;

import br.com.playbox.client.WhatsAppSender;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Coleta;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.ColetaRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ColetaService {

    private static final Logger log = LoggerFactory.getLogger(ColetaService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Locale LOCALE_BR = Locale.forLanguageTag("pt-BR");
    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");

    private final ColetaRepository coletaRepository;
    private final MaquinaRepository maquinaRepository;
    private final TransacaoRepository transacaoRepository;
    private final TransactionImportService transactionImportService;
    private final WhatsAppSender whatsAppSender;
    private final AppProperties properties;
    private final ConfiguracaoService configuracaoService;

    @Autowired
    public ColetaService(
            ColetaRepository coletaRepository,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            TransactionImportService transactionImportService,
            AppProperties properties,
            ObjectMapper objectMapper,
            @Autowired(required = false) ConfiguracaoService configuracaoService
    ) {
        this.coletaRepository = coletaRepository;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.transactionImportService = transactionImportService;
        this.properties = properties;
        this.configuracaoService = configuracaoService;

        AppProperties.Whatsapp wa = properties.getWhatsapp();
        String baseUrl = (wa != null && wa.getBaseUrl() != null) ? wa.getBaseUrl() : "http://whatsapp-bot:3035";
        String sendPath = (wa != null && wa.getSendPath() != null) ? wa.getSendPath() : "/api/messages/send";
        String apiKey = (wa != null && wa.getApiKey() != null) ? wa.getApiKey() : "";
        String chatId = (wa != null && wa.getColetaChatId() != null) ? wa.getColetaChatId() : "";

        this.whatsAppSender = new WhatsAppSender(
                baseUrl,
                apiKey,
                chatId,
                sendPath,
                properties.getTimeoutSeconds(),
                (objectMapper != null) ? objectMapper : new ObjectMapper()
        );

        log.info("ColetaService configurado com WhatsApp endpoint: {}{}, coletaChatId: {}", baseUrl, sendPath, chatId);
    }

    public record PreviaColetaDTO(
            Long maquinaId,
            String maquinaNome,
            String integracao,
            String externalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            LocalDateTime dataHoraColeta,
            LocalDateTime dataHoraUltimaColeta,
            Double valorEsperado,
            Double totalEntradasEspecie,
            Double totalFaturadoPeriodo,
            Double quantidadePremiosPeriodo,
            boolean primeiraColeta,
            int diasApurados,
            String periodoFormatado
    ) {}

    public record StatusProximaColetaDTO(
            Long maquinaId,
            String maquinaNome,
            String integracao,
            String externalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            String cidade,
            LocalDateTime dataHoraUltimaColeta,
            int diasSemColeta,
            boolean primeiraColeta,
            Double valorEsperado,
            Double faturamentoTotalPeriodo,
            Double quantidadePremiosPeriodo,
            String statusUrgencia
    ) {}

    public record PainelProximasColetasDTO(
            Double totalEsperadoCofres,
            int totalMaquinas,
            int maquinasComSaldo,
            int maquinasAlerta,
            List<StatusProximaColetaDTO> maquinas
    ) {}

    public PreviaColetaDTO calcularPrevia(Long maquinaId, LocalDateTime dataHoraColeta) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina não encontrada para o ID: " + maquinaId));

        LocalDateTime dataHora = (dataHoraColeta != null) ? dataHoraColeta : LocalDateTime.now(ZONE_SP);

        // Buscar a última coleta válida (não cancelada) registrada antes desta dataHora
        Optional<Coleta> optUltima = coletaRepository.findFirstByMaquinaIdAndStatusNotAndDataHoraColetaBeforeOrderByDataHoraColetaDesc(maquinaId, "CANCELADA", dataHora);

        LocalDateTime ultimaDataHora = optUltima.map(Coleta::getDataHoraColeta).orElse(null);
        boolean primeiraColeta = (ultimaDataHora == null);

        LocalDate dataFim = dataHora.toLocalDate();
        LocalDate dataInicioImportacao = primeiraColeta
                ? dataFim.minusDays(30).withDayOfMonth(1)
                : ultimaDataHora.toLocalDate();

        // Garantir sincronização das transações recentes e do período
        try {
            transactionImportService.ensureTransactionsImported(dataInicioImportacao, dataFim);
            transactionImportService.importDailyTransactions(dataFim.minusDays(1));
            transactionImportService.importDailyTransactions(dataFim);
        } catch (Exception e) {
            log.warn("Aviso ao atualizar transações na prévia da coleta para máquina {}: {}", maquinaId, e.getMessage());
        }

        Double valorEsperado;
        Double totalFaturado;
        Double totalPremios;
        int diasApurados;
        String periodoFormatado;

        if (primeiraColeta) {
            // Marco zero: soma tudo que houver até o dia da coleta
            valorEsperado = transacaoRepository.sumValorCashByMaquinaUntil(maquinaId, dataFim.atTime(LocalTime.MAX));
            totalFaturado = transacaoRepository.sumValorTotalByMaquinaUntil(maquinaId, dataFim.atTime(LocalTime.MAX));
            totalPremios = transacaoRepository.sumQuantidadePremiosByMaquinaUntil(maquinaId, dataFim.atTime(LocalTime.MAX));
            diasApurados = 30;
            periodoFormatado = "Marco Zero (Até " + dataHora.format(DATE_TIME_FORMATTER) + ")";
        } else {
            // A partir da segunda coleta: soma do dia seguinte à última coleta até o dia da coleta atual
            LocalDate startDay = ultimaDataHora.toLocalDate().isEqual(dataFim)
                    ? dataFim.plusDays(1)
                    : ultimaDataHora.toLocalDate().plusDays(1);

            LocalDateTime startDt = startDay.atStartOfDay();
            LocalDateTime endDt = dataFim.atTime(LocalTime.MAX);

            if (startDt.isAfter(endDt)) {
                valorEsperado = 0.0;
                totalFaturado = 0.0;
                totalPremios = 0.0;
                diasApurados = 0;
            } else {
                valorEsperado = transacaoRepository.sumValorCashByMaquinaAndPeriod(maquinaId, startDt, endDt);
                totalFaturado = transacaoRepository.sumValorTotalByMaquinaAndPeriod(maquinaId, startDt, endDt);
                totalPremios = transacaoRepository.sumQuantidadePremiosByMaquinaAndPeriod(maquinaId, startDt, endDt);
                diasApurados = (int) java.time.temporal.ChronoUnit.DAYS.between(ultimaDataHora.toLocalDate(), dataFim);
                if (diasApurados < 1) diasApurados = 1;
            }
            periodoFormatado = "De " + ultimaDataHora.format(DATE_TIME_FORMATTER) + " até " + dataHora.format(DATE_TIME_FORMATTER);
        }

        if (valorEsperado == null) valorEsperado = 0.0;
        if (totalFaturado == null) totalFaturado = 0.0;
        if (totalPremios == null) totalPremios = 0.0;

        double valorEsperadoArredondado = Math.round(valorEsperado * 100.0) / 100.0;
        double totalFaturadoArredondado = Math.round(totalFaturado * 100.0) / 100.0;
        double totalPremiosArredondado = Math.round(totalPremios * 100.0) / 100.0;

        String nomeMaq = maquina.getNomeIntegracao() != null ? maquina.getNomeIntegracao() : maquina.getDescricao();
        Estabelecimento est = maquina.getEstabelecimento();

        return new PreviaColetaDTO(
                maquina.getId(),
                nomeMaq,
                maquina.getIntegracao(),
                maquina.getExternalId(),
                est != null ? est.getId() : null,
                est != null ? est.getNome() : "Sem Estabelecimento",
                dataHora,
                ultimaDataHora,
                valorEsperadoArredondado,
                valorEsperadoArredondado,
                totalFaturadoArredondado,
                totalPremiosArredondado,
                primeiraColeta,
                diasApurados,
                periodoFormatado
        );
    }

    public PainelProximasColetasDTO obterPainelProximasColetas() {
        List<Maquina> maquinas = maquinaRepository.findByAtivoTrue();
        if (maquinas.isEmpty()) {
            maquinas = maquinaRepository.findAll();
        }

        LocalDateTime agora = LocalDateTime.now(ZONE_SP);
        LocalDate hoje = agora.toLocalDate();

        List<StatusProximaColetaDTO> lista = new ArrayList<>();
        double totalGeral = 0.0;
        int comSaldo = 0;
        int alerta = 0;

        for (Maquina m : maquinas) {
            Optional<Coleta> optUltima = coletaRepository.findFirstByMaquinaIdAndStatusNotOrderByDataHoraColetaDesc(m.getId(), "CANCELADA");
            boolean primeira = optUltima.isEmpty();
            LocalDateTime ultimaDt = optUltima.map(Coleta::getDataHoraColeta).orElse(null);

            Double esperado;
            Double faturamento;
            Double premios;
            int dias;

            if (primeira) {
                esperado = transacaoRepository.sumValorCashByMaquinaUntil(m.getId(), hoje.atTime(LocalTime.MAX));
                faturamento = transacaoRepository.sumValorTotalByMaquinaUntil(m.getId(), hoje.atTime(LocalTime.MAX));
                premios = transacaoRepository.sumQuantidadePremiosByMaquinaUntil(m.getId(), hoje.atTime(LocalTime.MAX));
                dias = 30; // Marco zero indicativo
            } else {
                LocalDate startDay = ultimaDt.toLocalDate().plusDays(1);
                LocalDateTime startDt = startDay.atStartOfDay();
                LocalDateTime endDt = hoje.atTime(LocalTime.MAX);

                dias = (int) java.time.temporal.ChronoUnit.DAYS.between(ultimaDt.toLocalDate(), hoje);
                if (dias < 0) dias = 0;

                if (startDt.isAfter(endDt)) {
                    esperado = 0.0;
                    faturamento = 0.0;
                    premios = 0.0;
                } else {
                    esperado = transacaoRepository.sumValorCashByMaquinaAndPeriod(m.getId(), startDt, endDt);
                    faturamento = transacaoRepository.sumValorTotalByMaquinaAndPeriod(m.getId(), startDt, endDt);
                    premios = transacaoRepository.sumQuantidadePremiosByMaquinaAndPeriod(m.getId(), startDt, endDt);
                }
            }

            if (esperado == null) esperado = 0.0;
            if (faturamento == null) faturamento = 0.0;
            if (premios == null) premios = 0.0;

            esperado = Math.round(esperado * 100.0) / 100.0;
            faturamento = Math.round(faturamento * 100.0) / 100.0;
            premios = Math.round(premios * 100.0) / 100.0;

            totalGeral += esperado;
            if (esperado > 0.0) comSaldo++;

            String urgencia = "NORMAL";
            if (dias >= 14 || esperado >= 600.0) {
                urgencia = "CRITICO";
                alerta++;
            } else if (dias >= 7 || esperado >= 300.0) {
                urgencia = "ATENCAO";
                alerta++;
            }

            String nomeMaq = m.getNomeIntegracao() != null ? m.getNomeIntegracao() : (m.getDescricao() != null ? m.getDescricao() : "Máquina #" + m.getId());
            Estabelecimento est = m.getEstabelecimento();

            lista.add(new StatusProximaColetaDTO(
                    m.getId(),
                    nomeMaq,
                    m.getIntegracao(),
                    m.getExternalId(),
                    est != null ? est.getId() : null,
                    est != null ? est.getNome() : "Sem Estabelecimento",
                    est != null && est.getCidade() != null ? est.getCidade() : "",
                    ultimaDt,
                    dias,
                    primeira,
                    esperado,
                    faturamento,
                    premios,
                    urgencia
            ));
        }

        // Ordenar primeiro por valor esperado decrescente, depois por dias sem coleta decrescente
        lista.sort((a, b) -> {
            int cmp = Double.compare(b.valorEsperado(), a.valorEsperado());
            if (cmp != 0) return cmp;
            return Integer.compare(b.diasSemColeta(), a.diasSemColeta());
        });

        totalGeral = Math.round(totalGeral * 100.0) / 100.0;

        return new PainelProximasColetasDTO(
                totalGeral,
                maquinas.size(),
                comSaldo,
                alerta,
                lista
        );
    }

    @Transactional
    public Coleta registrarColeta(
            Long maquinaId,
            LocalDateTime dataHoraColeta,
            Double valorEsperadoOverride,
            Double valorReal,
            String observacoes,
            String responsavel
    ) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina não encontrada para o ID: " + maquinaId));

        LocalDateTime dataHora = (dataHoraColeta != null) ? dataHoraColeta : LocalDateTime.now(ZONE_SP);

        PreviaColetaDTO previa = calcularPrevia(maquinaId, dataHora);
        Double valorEsperado = (valorEsperadoOverride != null) ? valorEsperadoOverride : previa.valorEsperado();

        Coleta coleta = new Coleta(
                maquina,
                maquina.getEstabelecimento(),
                dataHora,
                previa.dataHoraUltimaColeta(),
                valorEsperado,
                valorReal,
                observacoes,
                responsavel
        );

        Coleta salvo = coletaRepository.save(coleta);
        log.info("Coleta ID {} registrada para máquina {} (status: {}, esperado: {})",
                salvo.getId(), maquina.getId(), salvo.getStatus(), salvo.getValorEsperado());

        // Disparo de notificação WhatsApp
        try {
            if (salvo.getValorReal() == null) {
                notificarColetaRegistrada(salvo, previa.primeiraColeta());
            } else {
                notificarColetaConferida(salvo);
            }
        } catch (Exception e) {
            log.error("Erro ao enviar notificação WhatsApp para coleta ID {}: {}", salvo.getId(), e.getMessage());
        }

        return salvo;
    }

    @Transactional
    public Coleta conferirColeta(
            Long coletaId,
            Double valorReal,
            LocalDateTime dataHoraConferencia,
            String observacoesAdicionais
    ) {
        Coleta coleta = coletaRepository.findById(coletaId)
                .orElseThrow(() -> new IllegalArgumentException("Coleta não encontrada para o ID: " + coletaId));

        LocalDateTime dataConf = (dataHoraConferencia != null) ? dataHoraConferencia : LocalDateTime.now(ZONE_SP);
        coleta.registrarConferencia(valorReal, dataConf, observacoesAdicionais);

        Coleta atualizado = coletaRepository.save(coleta);
        log.info("Coleta ID {} conferida com sucesso (status: {}, real: {}, esperado: {}, dif: {})",
                atualizado.getId(), atualizado.getStatus(), atualizado.getValorReal(), atualizado.getValorEsperado(), atualizado.getDiferenca());

        try {
            notificarColetaConferida(atualizado);
        } catch (Exception e) {
            log.error("Erro ao enviar notificação de conferência da coleta ID {}: {}", atualizado.getId(), e.getMessage());
        }

        return atualizado;
    }

    public List<Coleta> listarPendentes() {
        return coletaRepository.findByStatusOrderByDataHoraColetaDesc("PENDENTE_CONFERENCIA");
    }

    public List<Coleta> listarTodas(Long maquinaId, Long estabelecimentoId, String status) {
        List<Coleta> todas = coletaRepository.findAllByOrderByDataHoraColetaDesc();
        return todas.stream()
                .filter(c -> maquinaId == null || (c.getMaquina() != null && c.getMaquina().getId().equals(maquinaId)))
                .filter(c -> estabelecimentoId == null || (c.getEstabelecimento() != null && c.getEstabelecimento().getId().equals(estabelecimentoId)))
                .filter(c -> status == null || status.isBlank() || status.equalsIgnoreCase(c.getStatus()))
                .toList();
    }

    public Optional<Coleta> buscarPorId(Long id) {
        return coletaRepository.findById(id);
    }

    @Transactional
    public Coleta cancelarColeta(Long id, String motivo) {
        Coleta coleta = coletaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Coleta não encontrada para o ID: " + id));

        if ("CANCELADA".equalsIgnoreCase(coleta.getStatus())) {
            throw new IllegalStateException("Esta coleta já está cancelada.");
        }

        String statusAnterior = coleta.getStatus();
        coleta.cancelar(motivo);
        Coleta salva = coletaRepository.save(coleta);

        log.info("Coleta ID {} cancelada com sucesso. Status anterior: {}, Motivo: {}",
                id, statusAnterior, motivo);

        try {
            notificarColetaCancelada(salva, motivo);
        } catch (Exception e) {
            log.error("Erro ao enviar notificação de cancelamento de coleta no WhatsApp: {}", e.getMessage());
        }

        return salva;
    }

    @Transactional
    public void excluirColeta(Long id) {
        if (!coletaRepository.existsById(id)) {
            throw new IllegalArgumentException("Coleta não encontrada para o ID: " + id);
        }
        coletaRepository.deleteById(id);
        log.info("Coleta ID {} excluída com sucesso.", id);
    }


    private void notificarColetaRegistrada(Coleta coleta, boolean primeiraColeta) {
        String nomeLocal = (coleta.getEstabelecimento() != null) ? coleta.getEstabelecimento().getNome() : "Sem Local";
        String nomeMaq = (coleta.getMaquina() != null && coleta.getMaquina().getNomeIntegracao() != null)
                ? coleta.getMaquina().getNomeIntegracao()
                : (coleta.getMaquina() != null ? coleta.getMaquina().getDescricao() : "Máquina");

        String dataStr = coleta.getDataHoraColeta().format(DATE_TIME_FORMATTER);
        String valorEspStr = formatMoeda(coleta.getValorEsperado());

        StringBuilder sb = new StringBuilder();
        if (primeiraColeta) {
            sb.append("📥 *MARCO ZERO DE COLETA REGISTRADO*\n\n");
        } else {
            sb.append("📥 *NOVA COLETA DE DINHEIRO REGISTRADA*\n\n");
        }

        sb.append("🏪 *Local:* ").append(nomeLocal).append("\n");
        sb.append("🕹️ *Máquina:* ").append(nomeMaq).append("\n");
        sb.append("📅 *Data/Hora da Retirada:* ").append(dataStr).append("\n");

        if (coleta.getDataHoraUltimaColeta() != null) {
            sb.append("🕒 *Última Coleta:* ").append(coleta.getDataHoraUltimaColeta().format(DATE_TIME_FORMATTER)).append("\n");
        }

        sb.append("\n💵 *Valor Esperado em Espécie:* ").append(valorEspStr).append("\n");
        sb.append("📊 *Status:* ⏳ *Aguardando Conferência de Caixa*\n");

        if (coleta.getResponsavel() != null && !coleta.getResponsavel().isBlank()) {
            sb.append("👤 *Responsável:* ").append(coleta.getResponsavel()).append("\n");
        }
        if (coleta.getObservacoes() != null && !coleta.getObservacoes().isBlank()) {
            sb.append("📝 *Obs:* ").append(coleta.getObservacoes()).append("\n");
        }

        sb.append("\n_Assim que a contagem física for feita, informe o valor real no sistema para fechar a conciliação._");

        String targetChatId = configuracaoService != null ? configuracaoService.getColetaChatId() : null;
        whatsAppSender.sendMessage(targetChatId, sb.toString());
    }

    private void notificarColetaConferida(Coleta coleta) {
        String nomeLocal = (coleta.getEstabelecimento() != null) ? coleta.getEstabelecimento().getNome() : "Sem Local";
        String nomeMaq = (coleta.getMaquina() != null && coleta.getMaquina().getNomeIntegracao() != null)
                ? coleta.getMaquina().getNomeIntegracao()
                : (coleta.getMaquina() != null ? coleta.getMaquina().getDescricao() : "Máquina");

        String dataRetirada = coleta.getDataHoraColeta().format(DATE_TIME_FORMATTER);
        String dataContagem = (coleta.getDataHoraConferencia() != null)
                ? coleta.getDataHoraConferencia().format(DATE_TIME_FORMATTER)
                : LocalDateTime.now(ZONE_SP).format(DATE_TIME_FORMATTER);

        double esp = coleta.getValorEsperado() != null ? coleta.getValorEsperado() : 0.0;
        double real = coleta.getValorReal() != null ? coleta.getValorReal() : 0.0;
        double dif = real - esp;

        StringBuilder sb = new StringBuilder();
        if (Math.abs(dif) < 0.01) {
            sb.append("✅ *CONFERÊNCIA DE CAIXA CONCLUÍDA (BATIMENTO 100% EXATO)*\n\n");
        } else if (dif < 0) {
            sb.append("⚠️ *CONFERÊNCIA DE CAIXA COM FALTA*\n\n");
        } else {
            sb.append("💰 *CONFERÊNCIA DE CAIXA COM SOBRA*\n\n");
        }

        sb.append("🏪 *Local:* ").append(nomeLocal).append("\n");
        sb.append("🕹️ *Máquina:* ").append(nomeMaq).append("\n");
        sb.append("📅 *Data da Retirada:* ").append(dataRetirada).append("\n");
        sb.append("🗓️ *Data da Contagem:* ").append(dataContagem).append("\n\n");

        sb.append("💵 *Valor Esperado:* ").append(formatMoeda(esp)).append("\n");
        sb.append("💵 *Valor Real Coletado:* ").append(formatMoeda(real)).append("\n");

        if (Math.abs(dif) < 0.01) {
            sb.append("⚖️ *Diferença:* R$ 0,00 (Batimento Exato ✅)\n");
            sb.append("📊 *Status:* Fechado e Conciliado\n");
        } else if (dif < 0) {
            sb.append("🔴 *Diferença:* -").append(formatMoeda(Math.abs(dif))).append(" (FALTA)\n");
            sb.append("📊 *Status:* ⚠️ Conferido com Falta de Caixa\n");
        } else {
            sb.append("🟢 *Diferença:* +").append(formatMoeda(dif)).append(" (SOBRA)\n");
            sb.append("📊 *Status:* ⚠️ Conferido com Sobra de Caixa\n");
        }

        if (coleta.getResponsavel() != null && !coleta.getResponsavel().isBlank()) {
            sb.append("👤 *Responsável:* ").append(coleta.getResponsavel()).append("\n");
        }
        if (coleta.getObservacoes() != null && !coleta.getObservacoes().isBlank()) {
            sb.append("📝 *Obs:* ").append(coleta.getObservacoes()).append("\n");
        }

        String targetChatId = configuracaoService != null ? configuracaoService.getColetaChatId() : null;
        whatsAppSender.sendMessage(targetChatId, sb.toString());
    }

    private void notificarColetaCancelada(Coleta coleta, String motivo) {
        String nomeLocal = (coleta.getEstabelecimento() != null) ? coleta.getEstabelecimento().getNome() : "Sem Local";
        String nomeMaq = (coleta.getMaquina() != null && coleta.getMaquina().getNomeIntegracao() != null)
                ? coleta.getMaquina().getNomeIntegracao()
                : (coleta.getMaquina() != null ? coleta.getMaquina().getDescricao() : "Máquina");

        String dataStr = coleta.getDataHoraColeta().format(DATE_TIME_FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("🚫 *COLETA DE DINHEIRO CANCELADA*\n\n");
        sb.append("🏪 *Local:* ").append(nomeLocal).append("\n");
        sb.append("🕹️ *Máquina:* ").append(nomeMaq).append("\n");
        sb.append("📅 *Data da Coleta:* ").append(dataStr).append("\n");
        sb.append("💵 *Valor Esperado:* ").append(formatMoeda(coleta.getValorEsperado())).append("\n");
        if (motivo != null && !motivo.isBlank()) {
            sb.append("⚠️ *Motivo:* ").append(motivo.trim()).append("\n");
        }
        sb.append("\n_O registro foi marcado como cancelado e não afetará a apuração dos próximos recolhimentos._");

        String targetChatId = configuracaoService != null ? configuracaoService.getColetaChatId() : null;
        whatsAppSender.sendMessage(targetChatId, sb.toString());
    }


    private static String formatMoeda(Double valor) {
        if (valor == null) return "R$ 0,00";
        return String.format(LOCALE_BR, "R$ %,.2f", valor);
    }
}
