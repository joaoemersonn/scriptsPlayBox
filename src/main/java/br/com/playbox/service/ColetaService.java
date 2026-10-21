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

    @Autowired
    public ColetaService(
            ColetaRepository coletaRepository,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            TransactionImportService transactionImportService,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this.coletaRepository = coletaRepository;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.transactionImportService = transactionImportService;
        this.properties = properties;

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
            boolean primeiraColeta,
            int diasApurados,
            String periodoFormatado
    ) {}

    public PreviaColetaDTO calcularPrevia(Long maquinaId, LocalDateTime dataHoraColeta) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina não encontrada para o ID: " + maquinaId));

        LocalDateTime dataHora = (dataHoraColeta != null) ? dataHoraColeta : LocalDateTime.now(ZONE_SP);

        // Buscar a última coleta registrada antes desta dataHora
        Optional<Coleta> optUltima = coletaRepository.findFirstByMaquinaIdAndDataHoraColetaBeforeOrderByDataHoraColetaDesc(maquinaId, dataHora);

        LocalDateTime ultimaDataHora = optUltima.map(Coleta::getDataHoraColeta).orElse(null);
        boolean primeiraColeta = (ultimaDataHora == null);

        LocalDate dataFim = dataHora.toLocalDate();

        // Sincronizar transações recentes (ontem e hoje) para garantir dados frescos do dia
        try {
            transactionImportService.importDailyTransactions(dataFim.minusDays(1));
            transactionImportService.importDailyTransactions(dataFim);
        } catch (Exception e) {
            log.warn("Aviso ao atualizar transações na prévia da coleta: {}", e.getMessage());
        }

        Double valorEsperado;
        int diasApurados;
        String periodoFormatado;

        if (primeiraColeta) {
            // Marco zero: soma tudo que houver até o dia da coleta
            valorEsperado = transacaoRepository.sumValorCashByMaquinaUntil(maquinaId, dataFim.atTime(LocalTime.MAX));
            diasApurados = 1;
            periodoFormatado = "Marco Zero (Até " + dataHora.format(DATE_TIME_FORMATTER) + ")";
        } else {
            // A partir da segunda coleta: soma do dia seguinte à última coleta até o dia da coleta atual
            LocalDate startDay = ultimaDataHora.toLocalDate().isEqual(dataFim)
                    ? dataFim
                    : ultimaDataHora.toLocalDate().plusDays(1);

            LocalDateTime startDt = startDay.atStartOfDay();
            LocalDateTime endDt = dataFim.atTime(LocalTime.MAX);

            valorEsperado = transacaoRepository.sumValorCashByMaquinaAndPeriod(maquinaId, startDt, endDt);
            diasApurados = (int) java.time.temporal.ChronoUnit.DAYS.between(startDay, dataFim) + 1;
            periodoFormatado = "De " + ultimaDataHora.format(DATE_TIME_FORMATTER) + " até " + dataHora.format(DATE_TIME_FORMATTER);
        }

        if (valorEsperado == null) {
            valorEsperado = 0.0;
        }

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
                Math.round(valorEsperado * 100.0) / 100.0,
                primeiraColeta,
                diasApurados,
                periodoFormatado
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

        whatsAppSender.sendMessage(sb.toString());
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

        whatsAppSender.sendMessage(sb.toString());
    }

    private static String formatMoeda(Double valor) {
        if (valor == null) return "R$ 0,00";
        return String.format(LOCALE_BR, "R$ %,.2f", valor);
    }
}
