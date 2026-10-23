package br.com.playbox.service;

import br.com.playbox.client.PayXYZClient;
import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MaquinaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class MachineSyncService {

    private static final Logger log = LoggerFactory.getLogger(MachineSyncService.class);

    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final AppProperties properties;
    private final MaquinaRepository maquinaRepository;
    private final EstabelecimentoRepository estabelecimentoRepository;

    public MachineSyncService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            EstabelecimentoRepository estabelecimentoRepository
    ) {
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
        this.maquinaRepository = maquinaRepository;
        this.estabelecimentoRepository = estabelecimentoRepository;
    }

    public List<Maquina> discoverAndSyncMachines() {
        log.info("Iniciando descoberta e sincronização de máquinas das integradoras...");
        List<Maquina> synced = new ArrayList<>();

        // 1. VendProMax
        try {
            String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
            List<JsonNode> vendMachines = vendClient.getMachines(token);
            for (JsonNode m : vendMachines) {
                if (m.hasNonNull("id")) {
                    String extId = m.get("id").asText();
                    String nome = m.hasNonNull("nome") ? m.get("nome").asText().trim() : "VendProMax " + extId;
                    Maquina maq = registerOrUpdate("VENDPROMAX", extId, nome);
                    synced.add(maq);
                }
            }
        } catch (Exception e) {
            log.error("Erro ao sincronizar máquinas do VendProMax: {}", e.getMessage());
        }

        // 2. PayXYZ
        try {
            String token = payxyzClient.login(properties.getPayxyz().getEmail(), properties.getPayxyz().getPassword(), true);
            List<JsonNode> payMachines = payxyzClient.getMachines(token);
            for (JsonNode m : payMachines) {
                if (m.hasNonNull("id")) {
                    String extId = m.get("id").asText();
                    String nome = m.hasNonNull("nome") ? m.get("nome").asText().trim() : "PayXYZ " + extId;
                    Maquina maq = registerOrUpdate("PAYXYZ", extId, nome);
                    synced.add(maq);
                }
            }
        } catch (Exception e) {
            log.error("Erro ao sincronizar máquinas do PayXYZ: {}", e.getMessage());
        }

        // 3. Zapizi
        try {
            JsonNode loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
            String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : null;
            Integer zapOrgId = properties.getZapizi().getOrganizationId();

            List<JsonNode> zapMachines = zapiziClient.getMachines(
                    token,
                    zapOrgId,
                    0,
                    1000,
                    "",
                    "",
                    "",
                    "",
                    "ALL",
                    List.of("POS"),
                    List.of()
            );

            for (JsonNode m : zapMachines) {
                String extId = null;
                if (m.hasNonNull("uniqueCode") && !m.get("uniqueCode").asText().isBlank()) {
                    extId = m.get("uniqueCode").asText().trim();
                } else if (m.hasNonNull("id")) {
                    extId = m.get("id").asText();
                }

                if (extId != null) {
                    String nome = null;
                    if (m.hasNonNull("locationName") && !m.get("locationName").asText().isBlank()) {
                        nome = m.get("locationName").asText().trim();
                    } else if (m.hasNonNull("nome") && !m.get("nome").asText().isBlank()) {
                        nome = m.get("nome").asText().trim();
                    } else {
                        nome = "Zapizi " + extId;
                    }
                    Maquina maq = registerOrUpdate("ZAPIZI", extId, nome);
                    synced.add(maq);
                }
            }
        } catch (Exception e) {
            log.error("Erro ao sincronizar máquinas do Zapizi: {}", e.getMessage());
        }

        log.info("Sincronização de máquinas finalizada. Total de máquinas verificadas: {}", synced.size());
        return synced;
    }

    public String fetchExternalMachineName(String integracao, String externalId) {
        if (integracao == null || externalId == null || externalId.isBlank()) return null;
        try {
            if ("VENDPROMAX".equalsIgnoreCase(integracao)) {
                String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
                List<JsonNode> vendMachines = vendClient.getMachines(token);
                for (JsonNode m : vendMachines) {
                    if (m.hasNonNull("id") && externalId.trim().equalsIgnoreCase(m.get("id").asText().trim())) {
                        return m.hasNonNull("nome") ? m.get("nome").asText().trim() : null;
                    }
                }
            } else if ("PAYXYZ".equalsIgnoreCase(integracao)) {
                String token = payxyzClient.login(properties.getPayxyz().getEmail(), properties.getPayxyz().getPassword(), true);
                List<JsonNode> payMachines = payxyzClient.getMachines(token);
                for (JsonNode m : payMachines) {
                    if (m.hasNonNull("id") && externalId.trim().equalsIgnoreCase(m.get("id").asText().trim())) {
                        return m.hasNonNull("nome") ? m.get("nome").asText().trim() : null;
                    }
                }
            } else if ("ZAPIZI".equalsIgnoreCase(integracao)) {
                JsonNode loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
                String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : null;
                Integer zapOrgId = properties.getZapizi().getOrganizationId();
                List<JsonNode> zapMachines = zapiziClient.getMachines(
                        token, zapOrgId, 0, 1000, "", "", "", "", "ALL", List.of("POS"), List.of()
                );
                for (JsonNode m : zapMachines) {
                    String ext = null;
                    if (m.hasNonNull("uniqueCode") && !m.get("uniqueCode").asText().isBlank()) {
                        ext = m.get("uniqueCode").asText().trim();
                    } else if (m.hasNonNull("id")) {
                        ext = m.get("id").asText().trim();
                    }
                    if (ext != null && externalId.trim().equalsIgnoreCase(ext)) {
                        if (m.hasNonNull("locationName") && !m.get("locationName").asText().isBlank()) {
                            return m.get("locationName").asText().trim();
                        }
                        if (m.hasNonNull("nome") && !m.get("nome").asText().isBlank()) {
                            return m.get("nome").asText().trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Não foi possível buscar nome na integradora {} para ID {}: {}", integracao, externalId, e.getMessage());
        }
        return null;
    }

    @Transactional
    public Maquina registerOrUpdate(String integracao, String externalId, String nomeIntegracao) {
        Optional<Maquina> opt = maquinaRepository.findByIntegracaoAndExternalId(integracao, externalId);
        if (opt.isPresent()) {
            // NUNCA sobrescrever o nome ou dados de máquina já cadastrada no banco pelo usuário!
            return opt.get();
        }

        // Nova máquina descoberta
        log.info("Nova máquina encontrada na integração {}: {} (ID externo: {})", integracao, nomeIntegracao, externalId);
        Maquina nova = new Maquina();
        nova.setIntegracao(integracao);
        nova.setExternalId(externalId);
        nova.setNomeIntegracao(nomeIntegracao);
        nova.setAtivo(true);

        return maquinaRepository.save(nova);
    }
}
