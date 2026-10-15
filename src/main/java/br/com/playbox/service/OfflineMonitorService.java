package br.com.playbox.service;

import br.com.playbox.client.*;
import br.com.playbox.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OfflineMonitorService {

    private static final Logger log = LoggerFactory.getLogger(OfflineMonitorService.class);

    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final WhatsAppSender sender;
    private final AppProperties properties;

    public OfflineMonitorService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties
    ) {
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
        AppProperties.Whatsapp wa = properties.getWhatsapp();
        String baseUrl = wa != null ? wa.getBaseUrl() : "http://whatsapp_bot:3035";
        String sendPath = wa != null ? wa.getSendPath() : "/api/messages/send";
        String chatId = wa != null && wa.getMonitorChatId() != null ? wa.getMonitorChatId() : "";
        String apiKey = wa != null ? wa.getApiKey() : "";

        this.sender = new WhatsAppSender(
                baseUrl,
                apiKey,
                chatId,
                sendPath,
                properties.getTimeoutSeconds(),
                null
        );
    }

    public synchronized void run() {
        log.info("Starting monitoramento");

        Path dataDir = resolveDataDirectory();
        Path previousOfflineFile = dataDir.resolve("offline_status.txt");
        Path previousMessageFile = dataDir.resolve("last_offline_notification.txt");
        Path notifiedBackOnlineFile = dataDir.resolve("notified_back_online.txt");

        Set<String> previousOffline = loadFileSet(previousOfflineFile);
        String previousMessage = loadFileText(previousMessageFile);
        Set<String> notifiedBackOnline = loadFileSet(notifiedBackOnlineFile);

        List<String> vendOffline;
        List<String> payOffline;
        List<String> zapOffline;

        try {
            String vendToken = vendClient.login(
                    properties.getVendpromax().getEmail(),
                    properties.getVendpromax().getPassword()
            );
            List<JsonNode> vendMachines = vendClient.getMachines(vendToken);
            vendOffline = getOfflineMachineNames(vendMachines);

            String payToken = payxyzClient.login(
                    properties.getPayxyz().getEmail(),
                    properties.getPayxyz().getPassword(),
                    true
            );
            List<JsonNode> payMachines = payxyzClient.getMachines(payToken);
            payOffline = getOfflineMachineNames(payMachines);

            JsonNode zapTokenData = zapiziClient.login(
                    properties.getZapizi().getEmail(),
                    properties.getZapizi().getPassword(),
                    null
            );
            String zapToken = (zapTokenData != null && zapTokenData.has("token"))
                    ? zapTokenData.get("token").asText()
                    : null;
            Integer zapOrgId = properties.getZapizi().getOrganizationId();

            List<JsonNode> zapMachines = zapiziClient.getMachines(
                    zapToken,
                    zapOrgId,
                    0,
                    1000,
                    "",
                    "",
                    "",
                    "",
                    "OFFLINE",
                    List.of("POS"),
                    List.of()
            );

            zapOffline = new ArrayList<>();
            for (JsonNode machine : zapMachines) {
                String status = machine.has("status") ? machine.get("status").asText("") : "";
                if (!"OFFLINE".equalsIgnoreCase(status)) {
                    continue;
                }
                String name = null;
                if (machine.hasNonNull("locationName") && !machine.get("locationName").asText().isBlank()) {
                    name = machine.get("locationName").asText();
                } else if (machine.hasNonNull("uniqueCode") && !machine.get("uniqueCode").asText().isBlank()) {
                    name = machine.get("uniqueCode").asText();
                } else if (machine.hasNonNull("name") && !machine.get("name").asText().isBlank()) {
                    name = machine.get("name").asText();
                }

                if (name != null && !name.trim().isBlank()) {
                    zapOffline.add(name.trim());
                }
            }
        } catch (IntegrationException error) {
            sendIntegrationError(error.getServiceName(), error.getDetails());
            return;
        }

        Set<String> currentOfflineSet = new TreeSet<>();
        currentOfflineSet.addAll(vendOffline);
        currentOfflineSet.addAll(payOffline);
        currentOfflineSet.addAll(zapOffline);
        List<String> currentOffline = new ArrayList<>(currentOfflineSet);

        log.info("Máquinas VendProMax offline: {}", vendOffline);
        log.info("Máquinas PayXYZ offline: {}", payOffline);
        log.info("Máquinas Zapizi offline: {}", zapOffline);
        log.info("Status anterior offline: {}", previousOffline.stream().sorted().toList());
        log.info("Status atual offline: {}", currentOffline);

        // Remove from notifiedBackOnline any machine that is currently offline
        notifiedBackOnline.removeAll(currentOfflineSet);

        // Check which machines became online
        Set<String> becameOnline = new TreeSet<>(previousOffline);
        becameOnline.removeAll(currentOfflineSet);

        List<String> newBackOnline = becameOnline.stream()
                .filter(name -> !notifiedBackOnline.contains(name))
                .sorted()
                .toList();

        String backOnlineMessage = buildMessage("*🟢DE VOLTA ONLINE:*", newBackOnline);
        if (!backOnlineMessage.isBlank()) {
            sendStatusAlert(backOnlineMessage);
            notifiedBackOnline.addAll(newBackOnline);
        }

        Set<String> oldOffline = new TreeSet<>(currentOfflineSet);
        oldOffline.retainAll(previousOffline);

        Set<String> newOffline = new TreeSet<>(currentOfflineSet);
        newOffline.removeAll(previousOffline);

        List<String> sections = new ArrayList<>();
        if (!newOffline.isEmpty()) {
            sections.add(buildMessage("*🔴NOVA MAQUINA OFFLINE:*", newOffline));
        }
        if (!oldOffline.isEmpty()) {
            sections.add(buildMessage("*🔴OFFLINE:*", oldOffline));
        }

        String offlineMessage = sections.stream()
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));

        saveFileSet(previousOfflineFile, currentOfflineSet);
        saveFileSet(notifiedBackOnlineFile, notifiedBackOnline);

        if (offlineMessage.isBlank()) {
            log.info("Nenhuma máquina offline.");
            return;
        }

        String messageText = offlineMessage.trim();
        if (messageText.equals(previousMessage.trim())) {
            log.info("Mensagem OFFLINE igual à anterior. Nenhuma notificação enviada.");
            return;
        }

        log.info("Mensagem OFFLINE gerada:\n{}", messageText);
        sendStatusAlert(messageText);
        saveFileText(previousMessageFile, messageText);
    }

    public List<String> getOfflineMachineNames(List<JsonNode> machines) {
        List<String> offline = new ArrayList<>();
        for (JsonNode machine : machines) {
            String status = null;
            if (machine.hasNonNull("status")) {
                status = machine.get("status").asText();
            } else if (machine.hasNonNull("statusMaquina")) {
                status = machine.get("statusMaquina").asText();
            }

            if (status != null && "OFFLINE".equalsIgnoreCase(status.trim())) {
                if (machine.hasNonNull("nome")) {
                    String name = machine.get("nome").asText().trim();
                    if (!name.isBlank()) {
                        offline.add(name);
                    }
                }
            }
        }
        return offline;
    }

    public String buildMessage(String title, Collection<String> machineNames) {
        List<String> names = machineNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();

        if (names.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add(title);
        for (String name : names) {
            lines.add("• " + name);
        }
        return String.join("\n", lines);
    }

    public void sendStatusAlert(String message) {
        if (message == null || message.isBlank()) {
            log.info("Nenhuma mudança de status para notificar.");
            return;
        }
        log.info("Enviando alerta de status via WhatsApp:\n{}", message);
        sender.sendMessage(message);
    }

    public void sendIntegrationError(String serviceName, String details) {
        String message = "Erro ao obter dados integracao: " + (serviceName != null ? serviceName : "desconhecido");
        if (details != null && !details.isBlank()) {
            message = message + "\n" + details;
        }
        log.error("Alerta de erro de integração: {}", message);
        sender.sendMessage(message);
    }

    private Path resolveDataDirectory() {
        Path configured = Paths.get(properties.getDataDir());
        try {
            if (!Files.exists(configured)) {
                Files.createDirectories(configured);
            }
        } catch (IOException e) {
            log.error("Erro ao preparar diretório de dados {}: {}", configured, e.getMessage());
        }
        return configured;
    }

    public Set<String> loadFileSet(Path filepath) {
        if (!Files.exists(filepath)) {
            return new HashSet<>();
        }
        try {
            return Files.readAllLines(filepath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            log.error("Erro ao ler arquivo {}: {}", filepath, e.getMessage());
            return new HashSet<>();
        }
    }

    public void saveFileSet(Path filepath, Collection<String> values) {
        try {
            List<String> sorted = values.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            Files.write(filepath, sorted, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Erro ao salvar arquivo {}: {}", filepath, e.getMessage());
        }
    }

    public String loadFileText(Path filepath) {
        if (!Files.exists(filepath)) {
            return "";
        }
        try {
            return Files.readString(filepath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Erro ao ler arquivo {}: {}", filepath, e.getMessage());
            return "";
        }
    }

    public void saveFileText(Path filepath, String text) {
        try {
            Files.writeString(filepath, text != null ? text : "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Erro ao salvar arquivo {}: {}", filepath, e.getMessage());
        }
    }
}

