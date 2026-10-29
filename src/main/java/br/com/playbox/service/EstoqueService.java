package br.com.playbox.service;

import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.MovimentacaoEstoque;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.MovimentacaoEstoqueRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EstoqueService {

    private static final Logger log = LoggerFactory.getLogger(EstoqueService.class);

    private final MaquinaRepository maquinaRepository;
    private final MovimentacaoEstoqueRepository movimentacaoRepository;
    private final VendProMaxClient vendClient;
    private final AppProperties properties;
    private final EstoqueAlertaService estoqueAlertaService;
    private final TransacaoRepository transacaoRepository;
    private final ZapiziClient zapiziClient;

    public record ResultadoAjusteEstoque(
            MovimentacaoEstoque movimentacao,
            int contagemInformada,
            int saidasPosteriores,
            int entradasPosteriores,
            int estoqueAtualResultante,
            int estoqueAnterior,
            boolean retroativo,
            String mensagem
    ) {}

    @org.springframework.beans.factory.annotation.Autowired
    public EstoqueService(
            MaquinaRepository maquinaRepository,
            MovimentacaoEstoqueRepository movimentacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties,
            EstoqueAlertaService estoqueAlertaService,
            TransacaoRepository transacaoRepository,
            ZapiziClient zapiziClient
    ) {
        this.maquinaRepository = maquinaRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.vendClient = vendClient;
        this.properties = properties;
        this.estoqueAlertaService = estoqueAlertaService;
        this.transacaoRepository = transacaoRepository;
        this.zapiziClient = zapiziClient;
    }

    public EstoqueService(
            MaquinaRepository maquinaRepository,
            MovimentacaoEstoqueRepository movimentacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties,
            EstoqueAlertaService estoqueAlertaService
    ) {
        this(maquinaRepository, movimentacaoRepository, vendClient, properties, estoqueAlertaService, null, null);
    }

    public EstoqueService(
            MaquinaRepository maquinaRepository,
            MovimentacaoEstoqueRepository movimentacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties
    ) {
        this(maquinaRepository, movimentacaoRepository, vendClient, properties, null, null, null);
    }

    /**
     * Consulta lista de máquinas e situação de estoque com filtros opcionais.
     */
    public List<Maquina> listarEstoque(Long estabelecimentoId, String cidade, Long maquinaId, Boolean apenasEstoqueBaixo) {
        boolean filtrarApenasBaixo = Boolean.TRUE.equals(apenasEstoqueBaixo);
        String cidadeFiltro = (cidade != null && !cidade.isBlank()) ? cidade.trim() : null;
        return maquinaRepository.filtrarEstoque(estabelecimentoId, cidadeFiltro, maquinaId, filtrarApenasBaixo);
    }

    public Optional<Maquina> buscarMaquinaPorId(Long maquinaId) {
        return maquinaRepository.findById(maquinaId);
    }

    /**
     * Adiciona pelúcias a uma máquina (abastecimento).
     */
    @Transactional
    public MovimentacaoEstoque adicionarPelucias(Long maquinaId, int quantidade, String observacao, String responsavel) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade adicionada deve ser maior que zero");
        }

        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina com ID " + maquinaId + " não encontrada"));

        int estoqueAnterior = maquina.getEstoqueAtual();
        int estoqueNovo = estoqueAnterior + quantidade;

        maquina.setEstoqueAtual(estoqueNovo);
        maquina.setUltimaEntradaEstoque(LocalDateTime.now());
        maquinaRepository.save(maquina);

        String obs = (observacao != null && !observacao.isBlank()) ? observacao : "Abastecimento manual de pelúcias";
        String resp = (responsavel != null && !responsavel.isBlank()) ? responsavel : "Usuário";

        MovimentacaoEstoque mov = new MovimentacaoEstoque(
                maquina,
                "ABASTECIMENTO",
                quantidade,
                estoqueAnterior,
                estoqueNovo,
                "API_MANUAL",
                obs,
                resp,
                LocalDateTime.now(),
                null
        );

        return movimentacaoRepository.save(mov);
    }

    /**
     * Define/ajusta o estoque atual diretamente (tempo real).
     */
    @Transactional
    public MovimentacaoEstoque definirEstoque(Long maquinaId, int novoEstoque, String observacao, String responsavel) {
        return definirEstoqueComResultado(maquinaId, novoEstoque, observacao, responsavel, null).movimentacao();
    }

    /**
     * Define/ajusta o estoque atual com suporte opcional a data/hora retroativa de contagem física.
     * Caso informado no passado, deduz automaticamente as saídas de prêmios ocorridas de lá pra cá.
     */
    @Transactional
    public ResultadoAjusteEstoque definirEstoqueComResultado(
            Long maquinaId,
            int novoEstoque,
            String observacao,
            String responsavel,
            LocalDateTime dataHoraContagem
    ) {
        if (novoEstoque < 0) {
            throw new IllegalArgumentException("O estoque não pode ser negativo");
        }

        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina com ID " + maquinaId + " não encontrada"));

        int estoqueAnterior = maquina.getEstoqueAtual();
        LocalDateTime agora = LocalDateTime.now();
        boolean isRetroativo = dataHoraContagem != null && dataHoraContagem.isBefore(agora.minusMinutes(2));

        int saidasPosteriores = 0;
        int entradasPosteriores = 0;
        int estoqueAtualResultante = novoEstoque;

        if (isRetroativo) {
            saidasPosteriores = calcularSaidasApos(maquina, dataHoraContagem);
            entradasPosteriores = movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(maquinaId, "ABASTECIMENTO", dataHoraContagem);
            estoqueAtualResultante = Math.max(0, novoEstoque - saidasPosteriores + entradasPosteriores);
        }

        int delta = estoqueAtualResultante - estoqueAnterior;
        maquina.setEstoqueAtual(estoqueAtualResultante);
        maquinaRepository.save(maquina);

        String tipo = isRetroativo ? "CONTAGEM_FISICA" : "AJUSTE_MANUAL";
        String obsPadrao = isRetroativo ? "Contagem física retroativa" : "Ajuste manual de estoque / inventário";
        String obsTexto = (observacao != null && !observacao.isBlank()) ? observacao : obsPadrao;

        if (isRetroativo) {
            obsTexto += String.format(" [Contagem em %s: %d un | Saídas: -%d | Estoque final: %d]",
                    dataHoraContagem.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    novoEstoque, saidasPosteriores, estoqueAtualResultante);
        }

        String resp = (responsavel != null && !responsavel.isBlank()) ? responsavel : "Usuário";
        LocalDateTime dataMov = (isRetroativo && dataHoraContagem != null) ? dataHoraContagem : agora;

        MovimentacaoEstoque mov = new MovimentacaoEstoque(
                maquina,
                tipo,
                delta,
                estoqueAnterior,
                estoqueAtualResultante,
                "API_MANUAL",
                obsTexto,
                resp,
                dataMov,
                null
        );

        MovimentacaoEstoque salvo = movimentacaoRepository.save(mov);

        String msg;
        if (isRetroativo) {
            msg = String.format("Contagem física de %d un em %s processada. Com %d saídas detectadas desde a contagem, o estoque atual foi recalculado para %d pelúcias.",
                    novoEstoque, dataHoraContagem.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), saidasPosteriores, estoqueAtualResultante);
        } else {
            msg = String.format("Estoque ajustado para %d pelúcias com sucesso!", estoqueAtualResultante);
        }

        return new ResultadoAjusteEstoque(
                salvo,
                novoEstoque,
                saidasPosteriores,
                entradasPosteriores,
                estoqueAtualResultante,
                estoqueAnterior,
                isRetroativo,
                msg
        );
    }

    /**
     * Calcula a prévia do recálculo retroativo de estoque sem salvar no banco de dados.
     */
    public ResultadoAjusteEstoque calcularPreviaRetroativa(Long maquinaId, int contagem, LocalDateTime dataHoraContagem) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina com ID " + maquinaId + " não encontrada"));

        int estoqueAnterior = maquina.getEstoqueAtual();
        LocalDateTime agora = LocalDateTime.now();
        boolean isRetroativo = dataHoraContagem != null && dataHoraContagem.isBefore(agora.minusMinutes(2));

        int saidasPosteriores = 0;
        int entradasPosteriores = 0;
        int estoqueAtualResultante = contagem;

        if (isRetroativo) {
            saidasPosteriores = calcularSaidasApos(maquina, dataHoraContagem);
            entradasPosteriores = movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(maquinaId, "ABASTECIMENTO", dataHoraContagem);
            estoqueAtualResultante = Math.max(0, contagem - saidasPosteriores + entradasPosteriores);
        }

        String msg = isRetroativo
                ? String.format("Contagem informada de %d em %s. Saídas posteriores detectadas: %d. Estoque resultante projetado: %d pelúcias.",
                contagem, dataHoraContagem.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), saidasPosteriores, estoqueAtualResultante)
                : "Ajuste em tempo real sem recálculo retroativo.";

        return new ResultadoAjusteEstoque(
                null,
                contagem,
                saidasPosteriores,
                entradasPosteriores,
                estoqueAtualResultante,
                estoqueAnterior,
                isRetroativo,
                msg
        );
    }

    /**
     * Calcula saídas de prêmios ocorridas após uma data/hora consultando integração e base local.
     */
    public int calcularSaidasApos(Maquina maquina, LocalDateTime dataHora) {
        if (maquina == null || dataHora == null) {
            return 0;
        }

        int saidasApi = 0;
        String integracao = maquina.getIntegracao() != null ? maquina.getIntegracao().toUpperCase() : "";

        if ("ZAPIZI".equals(integracao) && zapiziClient != null) {
            saidasApi = obterSaidasPremiosZapizi(maquina.getExternalId(), dataHora, LocalDateTime.now());
        } else if ("VENDPROMAX".equals(integracao) && vendClient != null) {
            saidasApi = obterSaidasPremiosVendProMax(maquina.getExternalId(), dataHora, LocalDateTime.now());
        }

        int saidasDbMov = movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(
                maquina.getId(), "SAIDA_PREMIO", dataHora
        );

        int saidasDbTx = 0;
        if (transacaoRepository != null) {
            Double sumTx = transacaoRepository.sumQuantidadePremiosByMaquinaAfter(maquina.getId(), dataHora);
            if (sumTx != null) {
                saidasDbTx = sumTx.intValue();
            }
        }

        return Math.max(saidasApi, Math.max(saidasDbMov, saidasDbTx));
    }

    public int obterSaidasPremiosZapizi(String machineUniqueCode, LocalDateTime inicio, LocalDateTime fim) {
        if (zapiziClient == null || machineUniqueCode == null || machineUniqueCode.isBlank()) {
            return 0;
        }
        try {
            ZoneId zoneSp = ZoneId.of("America/Sao_Paulo");
            String startIso = inicio.atZone(zoneSp).toInstant().toString();
            String endIso = fim.atZone(zoneSp).toInstant().toString();
            Integer orgId = (properties != null && properties.getZapizi() != null) ? properties.getZapizi().getOrganizationId() : 3852;

            JsonNode loginData = null;
            if (properties != null && properties.getZapizi() != null && properties.getZapizi().getEmail() != null) {
                loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
            }
            String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : zapiziClient.getLastToken();

            if (token != null && !token.isBlank()) {
                JsonNode reports = zapiziClient.getReports(token, startIso, endIso, null, null, null, null, 0, 100, orgId, null);
                if (reports != null && reports.hasNonNull("data") && reports.get("data").isArray()) {
                    for (JsonNode item : reports.get("data")) {
                        if (item.hasNonNull("machineUniqueCode") && machineUniqueCode.equalsIgnoreCase(item.get("machineUniqueCode").asText().trim())) {
                            if (item.hasNonNull("productsDelivered")) {
                                return item.get("productsDelivered").asInt();
                            }
                        }
                    }
                }

                JsonNode telemetry = zapiziClient.getTelemetry(token, startIso, endIso, orgId, null);
                if (telemetry != null && telemetry.hasNonNull("telemetryList") && telemetry.get("telemetryList").isArray()) {
                    for (JsonNode item : telemetry.get("telemetryList")) {
                        if (item.hasNonNull("machineUniqueCode") && machineUniqueCode.equalsIgnoreCase(item.get("machineUniqueCode").asText().trim())) {
                            if (item.hasNonNull("products")) {
                                return item.get("products").asInt();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Aviso ao consultar saídas de prêmios Zapizi para máquina {}: {}", machineUniqueCode, e.getMessage());
        }
        return 0;
    }

    public int obterSaidasPremiosVendProMax(String externalId, LocalDateTime inicio, LocalDateTime fim) {
        if (vendClient == null || externalId == null || properties == null || properties.getVendpromax() == null) {
            return 0;
        }
        try {
            String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
            if (token != null) {
                String dtInicio = inicio.toLocalDate().toString();
                String dtFim = fim.toLocalDate().toString();
                JsonNode rep = vendClient.getReport(token, "relatorio-premios", externalId, dtInicio, dtFim, false);
                if (rep != null && rep.hasNonNull("quantidade")) {
                    return rep.get("quantidade").asInt();
                }
            }
        } catch (Exception e) {
            log.warn("Aviso ao consultar prêmios VendProMax para máquina {}: {}", externalId, e.getMessage());
        }
        return 0;
    }

    /**
     * Configura limites de alerta mínimo e capacidade máxima da máquina.
     */
    @Transactional
    public Maquina configurarLimites(Long maquinaId, Integer estoqueMinimoAlerta, Integer capacidadeMaxima) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina com ID " + maquinaId + " não encontrada"));

        if (estoqueMinimoAlerta != null && estoqueMinimoAlerta >= 0) {
            maquina.setEstoqueMinimoAlerta(estoqueMinimoAlerta);
        }
        if (capacidadeMaxima != null && capacidadeMaxima > 0) {
            maquina.setCapacidadeMaxima(capacidadeMaxima);
        }

        return maquinaRepository.save(maquina);
    }

    /**
     * Registra saída automática de prêmios oriunda da sincronização de transações.
     * Totalmente idempotente através de externalRef = "SAIDA_" + externalTxId.
     */
    @Transactional
    public void processarSaidaAutomatica(
            Maquina maquina,
            LocalDate data,
            int quantidadePremios,
            String integracao,
            String externalTxId
    ) {
        if (maquina == null || quantidadePremios <= 0 || externalTxId == null) {
            return;
        }

        String ref = "SAIDA_" + externalTxId;
        Optional<MovimentacaoEstoque> existing = movimentacaoRepository.findByExternalRef(ref);

        if (existing.isEmpty()) {
            int anterior = maquina.getEstoqueAtual();
            int novo = Math.max(0, anterior - quantidadePremios);

            maquina.setEstoqueAtual(novo);
            LocalDateTime dataSaida = data != null ? data.atStartOfDay() : LocalDateTime.now();
            maquina.setUltimaSaidaEstoque(dataSaida);
            maquinaRepository.save(maquina);

            MovimentacaoEstoque mov = new MovimentacaoEstoque(
                    maquina,
                    "SAIDA_PREMIO",
                    -quantidadePremios,
                    anterior,
                    novo,
                    integracao,
                    "Saída de prêmios registrada via integração " + integracao,
                    "SISTEMA",
                    dataSaida,
                    ref
            );
            movimentacaoRepository.save(mov);
            log.info("Saída automática de {} prêmio(s) registrada para máquina {} (Estoque {} -> {})",
                    quantidadePremios, maquina.getId(), anterior, novo);
        } else {
            MovimentacaoEstoque mov = existing.get();
            int qtdRegistradaAnterior = Math.abs(mov.getQuantidade());
            if (qtdRegistradaAnterior != quantidadePremios) {
                int deltaPremios = quantidadePremios - qtdRegistradaAnterior; // Mais saídas
                int anterior = maquina.getEstoqueAtual();
                int novo = Math.max(0, anterior - deltaPremios);

                maquina.setEstoqueAtual(novo);
                maquinaRepository.save(maquina);

                mov.setQuantidade(-quantidadePremios);
                mov.setEstoqueNovo(novo);
                movimentacaoRepository.save(mov);
                log.info("Atualização de saída de prêmios para máquina {}: alterado de {} para {} (Novo estoque: {})",
                        maquina.getId(), qtdRegistradaAnterior, quantidadePremios, novo);
            }
        }
    }

    /**
     * Sincroniza o estoque nativo retornado pela API do VendProMax.
     */
    @Transactional
    public List<Maquina> sincronizarEstoqueVendProMax() {
        List<Maquina> atualizadas = new ArrayList<>();
        try {
            String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
            List<JsonNode> machines = vendClient.getMachines(token);

            for (JsonNode m : machines) {
                if (!m.hasNonNull("id")) continue;
                String extId = m.get("id").asText();

                Optional<Maquina> opt = maquinaRepository.findByIntegracaoAndExternalId("VENDPROMAX", extId);
                if (opt.isPresent()) {
                    Maquina maq = opt.get();
                    Integer estoqueNativo = null;
                    if (m.hasNonNull("estoque")) {
                        estoqueNativo = m.get("estoque").asInt();
                    } else if (m.hasNonNull("estoquepelucia")) {
                        estoqueNativo = m.get("estoquepelucia").asInt();
                    }

                    if (estoqueNativo != null) {
                        int anterior = maq.getEstoqueAtual();
                        if (anterior != estoqueNativo) {
                            int delta = estoqueNativo - anterior;
                            maq.setEstoqueAtual(estoqueNativo);
                            maquinaRepository.save(maq);

                            MovimentacaoEstoque mov = new MovimentacaoEstoque(
                                    maq,
                                    "SINCRONIZACAO",
                                    delta,
                                    anterior,
                                    estoqueNativo,
                                    "VENDPROMAX",
                                    "Sincronização com estoque nativo do VendProMax",
                                    "SISTEMA",
                                    LocalDateTime.now(),
                                    null
                            );
                            movimentacaoRepository.save(mov);
                            atualizadas.add(maq);
                        }
                    }
                }
            }
            log.info("Sincronização de estoque VendProMax finalizada. {} máquinas atualizadas.", atualizadas.size());
        } catch (Exception e) {
            log.error("Erro ao sincronizar estoque com VendProMax: {}", e.getMessage(), e);
        }
        return atualizadas;
    }

    /**
     * Busca histórico de movimentações de uma máquina.
     */
    public List<MovimentacaoEstoque> buscarHistorico(Long maquinaId, LocalDate inicio, LocalDate fim) {
        if (inicio != null && fim != null) {
            LocalDateTime dtInicio = inicio.atStartOfDay();
            LocalDateTime dtFim = fim.atTime(LocalTime.MAX);
            return movimentacaoRepository.findByMaquinaIdAndDataMovimentacaoBetweenOrderByDataMovimentacaoDesc(maquinaId, dtInicio, dtFim);
        }
        return movimentacaoRepository.findByMaquinaIdOrderByDataMovimentacaoDesc(maquinaId);
    }
}
