package br.com.playbox.service;

import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.MovimentacaoEstoque;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.MovimentacaoEstoqueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    public EstoqueService(
            MaquinaRepository maquinaRepository,
            MovimentacaoEstoqueRepository movimentacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties
    ) {
        this.maquinaRepository = maquinaRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.vendClient = vendClient;
        this.properties = properties;
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
     * Define/ajusta o estoque atual diretamente (balanço/contagem física).
     */
    @Transactional
    public MovimentacaoEstoque definirEstoque(Long maquinaId, int novoEstoque, String observacao, String responsavel) {
        if (novoEstoque < 0) {
            throw new IllegalArgumentException("O estoque não pode ser negativo");
        }

        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina com ID " + maquinaId + " não encontrada"));

        int estoqueAnterior = maquina.getEstoqueAtual();
        int delta = novoEstoque - estoqueAnterior;

        maquina.setEstoqueAtual(novoEstoque);
        maquinaRepository.save(maquina);

        String obs = (observacao != null && !observacao.isBlank()) ? observacao : "Ajuste manual de estoque / inventário";
        String resp = (responsavel != null && !responsavel.isBlank()) ? responsavel : "Usuário";

        MovimentacaoEstoque mov = new MovimentacaoEstoque(
                maquina,
                "AJUSTE_MANUAL",
                delta,
                estoqueAnterior,
                novoEstoque,
                "API_MANUAL",
                obs,
                resp,
                LocalDateTime.now(),
                null
        );

        return movimentacaoRepository.save(mov);
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
