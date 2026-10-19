package br.com.playbox.service;

import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.repository.EstabelecimentoRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataInitializationService {

    private static final Logger log = LoggerFactory.getLogger(DataInitializationService.class);

    private final EstabelecimentoRepository estabelecimentoRepository;
    private final MachineSyncService machineSyncService;

    public DataInitializationService(
            EstabelecimentoRepository estabelecimentoRepository,
            MachineSyncService machineSyncService
    ) {
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.machineSyncService = machineSyncService;
    }

    @PostConstruct
    public void init() {
        if (estabelecimentoRepository.count() == 0) {
            log.info("Inicializando estabelecimentos da planilha no banco de dados...");
            seedEstabelecimentos();
        }

        // Tenta sincronizar máquinas existentes das integrações no startup
        try {
            machineSyncService.discoverAndSyncMachines();
        } catch (Exception e) {
            log.warn("Não foi possível sincronizar máquinas no startup (APIs podem estar indisponíveis): {}", e.getMessage());
        }
    }

    private void seedEstabelecimentos() {
        List<Estabelecimento> list = List.of(
                new Estabelecimento("Pau Brasil", "Custódia", "Diogo (87) 99670-6876", "13638774457", "Dia 01", 150.0, "2026-07-31"),
                new Estabelecimento("Br Arena", "Monteiro", "Gabrielly (83) 99850-8298", "055.824.914-06", "Dia 02", 150.0, "2026-06-02"),
                new Estabelecimento("Praça do Sabor", "Custódia", "Gabriel (87) 99681-9232", "Em Espécie💵", "Dia 06", 150.0, "2025-01-01"),
                new Estabelecimento("Espaço Villa", "Maravilha", "Daniela (87) 98127-0450", "pix cpf", "Dia 06", 150.0, "2025-01-01"),
                new Estabelecimento("Posto Iguracy", "Iguaracy", "Paulinho (87) 99677-4513", "33.802.987.0001-26", "Dia 08", 200.0, "2026-04-08"),
                new Estabelecimento("Posto FAN", "Monteiro", "Barbara (83) 99635-0786", "barbrinha_celene@hotmail.com", "Dia 17", 150.0, "2026-04-17"),
                new Estabelecimento("Casa Nobre", "Sertânia", "Paulão (87) 98811-0445", "Em Espécie💵", "Dia 21", 150.0, "2026-04-21"),
                new Estabelecimento("Lan House Wolrd Player", "Custódia", "Matheus (87) 99680-9497", "Em Espécie💵 matheus_olive97@hotmail.com", "Dia 21", 150.0, "2026-07-21"),
                new Estabelecimento("Shopping25", "Sertânia", "Adenilma (83) 99693-5828", "(83)9 9693-5828", "Dia 21", 150.0, "2026-07-21"),
                new Estabelecimento("Menininho", "Monteiro", "Joao Victor (83) 9 9988-6453", "16475785407", "Dia 21", 150.0, "2026-08-22"),
                new Estabelecimento("Adega Sumé", "Sumé", "Daniel (83) 99817-9768", "", "Dia 02", 150.0, "2026-10-02"),
                new Estabelecimento("PingMel", "Custódia", "", "", "Dia 06", 150.0, ""),
                new Estabelecimento("San Marino", "Custódia", "", "", "Dia 11", 63.0, "2026-04-11"),
                new Estabelecimento("PitStop", "Sertânia", "", "", "Dia 18", 150.0, "2026-05-18"),
                new Estabelecimento("Lan. Dois Irmaõs", "Custódia", "", "", "Dia 07", 150.0, "2026-04-07")
        );

        estabelecimentoRepository.saveAll(list);
        log.info("{} estabelecimentos cadastrados com sucesso.", list.size());
    }
}
