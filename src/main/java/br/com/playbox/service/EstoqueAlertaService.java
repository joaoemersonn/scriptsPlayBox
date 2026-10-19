package br.com.playbox.service;

import br.com.playbox.client.WhatsAppSender;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.MaquinaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class EstoqueAlertaService {

    private static final Logger log = LoggerFactory.getLogger(EstoqueAlertaService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    public static final String DEFAULT_ESTOQUE_CHAT_ID = "120363423381929568@g.us";

    private final MaquinaRepository maquinaRepository;
    private final WhatsAppSender whatsAppSender;
    private final AppProperties properties;

    @Autowired
    public EstoqueAlertaService(
            MaquinaRepository maquinaRepository,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this.maquinaRepository = maquinaRepository;
        this.properties = properties;

        AppProperties.Whatsapp wa = properties.getWhatsapp();
        String baseUrl = (wa != null && wa.getBaseUrl() != null) ? wa.getBaseUrl() : "http://whatsapp-bot:3035";
        String sendPath = (wa != null && wa.getSendPath() != null) ? wa.getSendPath() : "/api/messages/send";
        String apiKey = (wa != null && wa.getApiKey() != null) ? wa.getApiKey() : "";
        String chatId = (wa != null && wa.getEstoqueChatId() != null && !wa.getEstoqueChatId().isBlank())
                ? wa.getEstoqueChatId()
                : DEFAULT_ESTOQUE_CHAT_ID;

        this.whatsAppSender = new WhatsAppSender(
                baseUrl,
                apiKey,
                chatId,
                sendPath,
                properties.getTimeoutSeconds(),
                (objectMapper != null) ? objectMapper : new ObjectMapper()
        );

        log.info("EstoqueAlertaService inicializado para o grupo: {}", chatId);
    }

    public EstoqueAlertaService(
            MaquinaRepository maquinaRepository,
            AppProperties properties,
            WhatsAppSender whatsAppSender
    ) {
        this.maquinaRepository = maquinaRepository;
        this.properties = properties;
        this.whatsAppSender = whatsAppSender;
    }

    private String getTargetChatId() {
        if (properties.getWhatsapp() != null && properties.getWhatsapp().getEstoqueChatId() != null && !properties.getWhatsapp().getEstoqueChatId().isBlank()) {
            return properties.getWhatsapp().getEstoqueChatId();
        }
        return DEFAULT_ESTOQUE_CHAT_ID;
    }

    /**
     * Envia o relatório diário consolidado das lojas que necessitam de reabastecimento (estoque < 30).
     * Executado diariamente por volta das 18h20.
     * Se nenhuma loja estiver abaixo do limite mínimo, não envia mensagem para não poluir o grupo.
     */
    @Transactional
    public int verificarEEnviarAlertas() {
        List<Maquina> baixas = maquinaRepository.findMaquinasComEstoqueBaixo();
        if (baixas.isEmpty()) {
            log.info("[ALERTA ESTOQUE] Nenhuma loja com estoque baixo (< 30) para reabastecimento hoje.");
            return 0;
        }

        LocalDateTime agora = LocalDateTime.now();
        String mensagem = formatarMensagemConsolidada(baixas, agora);
        boolean enviado = whatsAppSender.sendMessage(getTargetChatId(), mensagem);

        if (enviado) {
            for (Maquina m : baixas) {
                m.setUltimaNotificacaoEstoqueBaixo(agora);
                maquinaRepository.save(m);
            }
            log.info("[ALERTA ESTOQUE] Relatório diário de reabastecimento enviado com sucesso para {} lojas no grupo {}",
                    baixas.size(), getTargetChatId());
            return baixas.size();
        } else {
            log.error("[ALERTA ESTOQUE] Falha ao enviar relatório diário de reabastecimento via WhatsApp.");
            return 0;
        }
    }

    /**
     * Notifica imediatamente quando o estoque de uma máquina específica cai abaixo do limite (< 30).
     */
    @Transactional
    public boolean notificarQuedaEstoque(Maquina maquina, int estoqueAnterior, int estoqueNovo) {
        if (maquina == null) return false;

        int limite = maquina.getEstoqueMinimoAlerta();

        // Se o estoque foi reabastecido (>= limite), reseta o indicador para alertar novamente em futura queda
        if (estoqueNovo >= limite) {
            if (maquina.getUltimaNotificacaoEstoqueBaixo() != null) {
                maquina.setUltimaNotificacaoEstoqueBaixo(null);
                maquinaRepository.save(maquina);
            }
            return false;
        }

        // Se caiu abaixo do limite:
        // Notifica se estava antes >= limite OU se nunca foi notificada
        boolean transicaoParaBaixo = (estoqueAnterior >= limite);
        boolean nuncaNotificada = (maquina.getUltimaNotificacaoEstoqueBaixo() == null);

        if (transicaoParaBaixo || nuncaNotificada) {
            LocalDateTime agora = LocalDateTime.now();
            String mensagem = formatarMensagemIndividual(maquina, estoqueAnterior, estoqueNovo, agora);
            boolean enviado = whatsAppSender.sendMessage(getTargetChatId(), mensagem);
            if (enviado) {
                maquina.setUltimaNotificacaoEstoqueBaixo(agora);
                maquinaRepository.save(maquina);
                log.info("[ALERTA ESTOQUE] Alerta de estoque crítico enviado para máquina {} (Estoque {} -> {})",
                        maquina.getId(), estoqueAnterior, estoqueNovo);
                return true;
            }
        }

        return false;
    }

    private String formatarMensagemIndividual(Maquina m, int anterior, int novo, LocalDateTime dataHora) {
        Estabelecimento est = m.getEstabelecimento();
        String nomeLoja = est != null ? est.getNome() : (m.getNomeIntegracao() != null ? m.getNomeIntegracao() : "Sem estabelecimento");
        String cidade = (est != null && est.getCidade() != null) ? est.getCidade() : "Não informada";
        int cap = m.getCapacidadeMaxima();
        double perc = cap > 0 ? Math.round((novo * 100.0 / cap) * 10.0) / 10.0 : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ *ALERTA DE ESTOQUE BAIXO - PLAYBOX* ⚠️\n\n");
        sb.append("A máquina abaixo atingiu nível crítico (*< ").append(m.getEstoqueMinimoAlerta()).append(" pelúcias*):\n\n");
        sb.append("📍 *Local:* ").append(nomeLoja).append(" (").append(cidade).append(")\n");
        sb.append("🎰 *Máquina:* ").append(m.getNomeIntegracao() != null ? m.getNomeIntegracao() : m.getDescricao())
                .append(" (#").append(m.getExternalId()).append(")\n");
        sb.append("🏷️ *Integração:* ").append(m.getIntegracao()).append("\n");
        sb.append("📦 *Estoque Atual:* *").append(novo).append(" pelúcias* (Anterior: ").append(anterior).append(")\n");
        sb.append("🎯 *Capacidade:* ").append(cap).append(" (").append(perc).append("%)\n");
        sb.append("⏱️ *Horário:* ").append(dataHora.format(FORMATTER)).append("\n\n");
        sb.append("🚨 _Favor providenciar o reabastecimento._");

        return sb.toString();
    }

    private String formatarMensagemConsolidada(List<Maquina> lista, LocalDateTime dataHora) {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 *RELATÓRIO DIÁRIO DE REABASTECIMENTO - PLAYBOX* 📦\n\n");
        sb.append("Lojas com estoque crítico (*< 30 pelúcias*):\n\n");

        for (Maquina m : lista) {
            Estabelecimento est = m.getEstabelecimento();
            String nomeLoja = (est != null && est.getNome() != null && !est.getNome().isBlank())
                    ? est.getNome()
                    : (m.getNomeIntegracao() != null ? m.getNomeIntegracao() : "Sem Loja");
            String cidade = (est != null && est.getCidade() != null && !est.getCidade().isBlank()) ? est.getCidade() : "-";
            int atual = m.getEstoqueAtual();
            int cap = m.getCapacidadeMaxima();

            sb.append("• *").append(nomeLoja).append("* (").append(cidade).append("): ")
                    .append("*").append(atual).append(" pelúcias*");
            if (cap > 0) {
                sb.append(" _[Capacidade: ").append(cap).append("]_");
            }
            sb.append("\n");
        }

        sb.append("\n⏱️ *Horário:* ").append(dataHora.format(FORMATTER)).append("\n");
        sb.append("🚨 _Favor programar a rota de reabastecimento._");

        return sb.toString();
    }
}
