package br.com.playbox.service;

import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.MeioPagamento;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MeioPagamentoRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;

@Service
public class DataInitializationService {

    private static final Logger log = LoggerFactory.getLogger(DataInitializationService.class);

    private final EstabelecimentoRepository estabelecimentoRepository;
    private final MeioPagamentoRepository meioPagamentoRepository;
    private final MachineSyncService machineSyncService;
    private final AluguelService aluguelService;
    private final TransactionImportService transactionImportService;
    private final br.com.playbox.repository.TransacaoRepository transacaoRepository;

    public DataInitializationService(
            EstabelecimentoRepository estabelecimentoRepository,
            MeioPagamentoRepository meioPagamentoRepository,
            MachineSyncService machineSyncService,
            AluguelService aluguelService,
            TransactionImportService transactionImportService,
            br.com.playbox.repository.TransacaoRepository transacaoRepository
    ) {
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.meioPagamentoRepository = meioPagamentoRepository;
        this.machineSyncService = machineSyncService;
        this.aluguelService = aluguelService;
        this.transactionImportService = transactionImportService;
        this.transacaoRepository = transacaoRepository;
    }

    @PostConstruct
    public void init() {
        if (estabelecimentoRepository.count() == 0) {
            log.info("Inicializando estabelecimentos e meios de pagamento da planilha no banco de dados...");
            seedEstabelecimentos();
        } else if (meioPagamentoRepository.count() == 0) {
            log.info("Populando meios de pagamento para estabelecimentos já existentes...");
            seedMeiosPagamentoParaExistentes();
        }

        // Praça do Sabor com aluguel dinâmico (25% sobre o lucro)
        estabelecimentoRepository.findByNomeIgnoreCase("Praça do Sabor").ifPresent(est -> {
            if (!est.isAluguelDinamico()) {
                est.setTipoAluguel("DINAMICO");
                est.setPercentualRepasse(25.0);
                estabelecimentoRepository.save(est);
                log.info("Estabelecimento Praça do Sabor configurado com aluguel dinâmico (25% sobre lucro)");
            }
        });

        // Limpar pendências de inativos e gerar aluguéis do mês atual caso não existam
        try {
            aluguelService.removerCobrancasPendentesDeInativos();
            aluguelService.gerarCobrancasMes(YearMonth.now());
        } catch (Exception e) {
            log.warn("Não foi possível processar cobranças de aluguel no startup: {}", e.getMessage());
        }

        // Importa transações do mês atual em background para não bloquear o startup do Tomcat
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                if (transacaoRepository.count() == 0) {
                    java.time.LocalDate hoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
                    java.time.LocalDate inicioMes = hoje.withDayOfMonth(1);
                    java.time.LocalDate ontem = hoje.minusDays(1);
                    if (!inicioMes.isAfter(ontem)) {
                        log.info("Banco sem transações. Inicializando importação em background do mês atual ({} a {})...", inicioMes, ontem);
                        transactionImportService.importTransactionsRange(inicioMes, ontem);
                    }
                }
            } catch (Exception e) {
                log.warn("Não foi possível importar transações no background: {}", e.getMessage());
            }
        }, "bg-tx-import").start();
    }

    private void seedEstabelecimentos() {
        createAndSave("Pau Brasil", "Custódia", "Diogo (87) 99670-6876", "Dia 01", 150.0, "2026-07-31", "PIX", "13638774457");
        createAndSave("Br Arena", "Monteiro", "Gabrielly (83) 99850-8298", "Dia 02", 150.0, "2026-06-02", "PIX", "055.824.914-06");
        createAndSave("Praça do Sabor", "Custódia", "Gabriel (87) 99681-9232", "Dia 06", 150.0, "2025-01-01", "ESPECIE", "Em Espécie💵");
        createAndSave("Espaço Villa", "Maravilha", "Daniela (87) 98127-0450", "Dia 06", 150.0, "2025-01-01", "PIX", "pix cpf");
        createAndSave("Posto Iguracy", "Iguaracy", "Paulinho (87) 99677-4513", "Dia 08", 200.0, "2026-04-08", "PIX", "33.802.987.0001-26");
        createAndSave("Posto FAN", "Monteiro", "Barbara (83) 99635-0786", "Dia 17", 150.0, "2026-04-17", "PIX", "barbrinha_celene@hotmail.com");
        createAndSave("Casa Nobre", "Sertânia", "Paulão (87) 98811-0445", "Dia 21", 150.0, "2026-04-21", "ESPECIE", "Em Espécie💵");
        createAndSave("Lan House Wolrd Player", "Custódia", "Matheus (87) 99680-9497", "Dia 21", 150.0, "2026-07-21", "PIX", "matheus_olive97@hotmail.com");
        createAndSave("Shopping25", "Sertânia", "Adenilma (83) 99693-5828", "Dia 21", 150.0, "2026-07-21", "PIX", "(83)9 9693-5828");
        createAndSave("Menininho", "Monteiro", "Joao Victor (83) 9 9988-6453", "Dia 21", 150.0, "2026-08-22", "PIX", "16475785407");
        createAndSave("Adega Sumé", "Sumé", "Daniel (83) 99817-9768", "Dia 02", 150.0, "2026-10-02", "ESPECIE", "Em Espécie💵");
        createAndSave("PingMel", "Custódia", "", "Dia 06", 150.0, "", "ESPECIE", "Em Espécie💵");
        createAndSave("San Marino", "Custódia", "", "Dia 11", 63.0, "2026-04-11", "ESPECIE", "Em Espécie💵");
        createAndSave("PitStop", "Sertânia", "", "Dia 18", 150.0, "2026-05-18", "ESPECIE", "Em Espécie💵");
        createAndSave("Lan. Dois Irmaõs", "Custódia", "", "Dia 07", 150.0, "2026-04-07", "ESPECIE", "Em Espécie💵");
    }

    private void seedMeiosPagamentoParaExistentes() {
        java.util.Map<String, String[]> defaults = java.util.Map.ofEntries(
                java.util.Map.entry("Pau Brasil", new String[]{"PIX", "13638774457"}),
                java.util.Map.entry("Br Arena", new String[]{"PIX", "055.824.914-06"}),
                java.util.Map.entry("Praça do Sabor", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("Espaço Villa", new String[]{"PIX", "pix cpf"}),
                java.util.Map.entry("Posto Iguracy", new String[]{"PIX", "33.802.987.0001-26"}),
                java.util.Map.entry("Posto FAN", new String[]{"PIX", "barbrinha_celene@hotmail.com"}),
                java.util.Map.entry("Casa Nobre", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("Lan House Wolrd Player", new String[]{"PIX", "matheus_olive97@hotmail.com"}),
                java.util.Map.entry("Shopping25", new String[]{"PIX", "(83)9 9693-5828"}),
                java.util.Map.entry("Menininho", new String[]{"PIX", "16475785407"}),
                java.util.Map.entry("Adega Sumé", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("PingMel", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("San Marino", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("PitStop", new String[]{"ESPECIE", "Em Espécie💵"}),
                java.util.Map.entry("Lan. Dois Irmaõs", new String[]{"ESPECIE", "Em Espécie💵"})
        );

        for (Estabelecimento est : estabelecimentoRepository.findAll()) {
            if (est.getMeiosPagamento() == null || est.getMeiosPagamento().isEmpty()) {
                String[] def = defaults.get(est.getNome());
                if (def != null) {
                    meioPagamentoRepository.save(new MeioPagamento(est, def[0], def[1], "Principal", true));
                }
            }
        }
    }

    private void createAndSave(String nome, String cidade, String responsavel, String diaPagamento, Double valor, String dataInicio, String tipoMeio, String chaveOuDetalhe) {
        Estabelecimento est = new Estabelecimento(nome, cidade, responsavel, diaPagamento, valor, dataInicio);
        if (chaveOuDetalhe != null && !chaveOuDetalhe.isBlank()) {
            est.addMeioPagamento(new MeioPagamento(est, tipoMeio, chaveOuDetalhe, "Principal", true));
        }
        estabelecimentoRepository.save(est);
    }
}
