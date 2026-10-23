package br.com.playbox.service;

import br.com.playbox.client.*;
import br.com.playbox.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OfflineMonitorService {

    private static final Logger log = LoggerFactory.getLogger(OfflineMonitorService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final WhatsAppSender sender;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public OfflineMonitorService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();

        AppProperties.Whatsapp wa = properties.getWhatsapp();
        String baseUrl = wa != null ? wa.getBaseUrl() : "http://whatsapp-bot:3035";
        String sendPath = wa != null ? wa.getSendPath() : "/api/messages/send";
        String chatId = wa != null && wa.getMonitorChatId() != null ? wa.getMonitorChatId() : "";
        String apiKey = wa != null ? wa.getApiKey() : "";

        this.sender = new WhatsAppSender(
                baseUrl,
                apiKey,
                chatId,
                sendPath,
                properties.getTimeoutSeconds(),
                this.objectMapper
        );

        log.info("OfflineMonitorService configurado com WhatsApp endpoint: {}{}, monitorChatId: {}",
                baseUrl, sendPath, chatId);
    }

    public OfflineMonitorService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties
    ) {
        this(vendClient, payxyzClient, zapiziClient, properties, new ObjectMapper());
    }

    public synchronized void run() {
        log.info("Starting monitoramento");

        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        Path dataDir = resolveDataDirectory();
        Path previousOfflineFile = dataDir.resolve("offline_status.txt");
        Path previousMessageFile = dataDir.resolve("last_offline_notification.txt");
        Path notifiedBackOnlineFile = dataDir.resolve("notified_back_online.txt");
        Path offlineTimestampsFile = dataDir.resolve("offline_timestamps.json");

        Set<String> previousOffline = loadFileSet(previousOfflineFile);
        previousOffline.removeIf(m -> m.toLowerCase().contains("sem loja"));
        String previousMessage = loadFileText(previousMessageFile);
        Set<String> notifiedBackOnline = loadFileSet(notifiedBackOnlineFile);
        Map<String, String> offlineTimestamps = loadOfflineTimestamps(offlineTimestampsFile);
        offlineTimestamps.keySet().removeIf(m -> m.toLowerCase().contains("sem loja"));

        Path failingIntegrationsFile = dataDir.resolve("failing_integrations.txt");
        Set<String> previousFailingIntegrations = loadFileSet(failingIntegrationsFile);
        Set<String> currentFailingIntegrations = new TreeSet<>();

        Map<String, String> vendOffline = Collections.emptyMap();
        Map<String, String> payOffline = Collections.emptyMap();
        Map<String, String> zapOffline = Collections.emptyMap();

        // 1. VendProMax
        try {
            String vendToken = vendClient.login(
                    properties.getVendpromax().getEmail(),
                    properties.getVendpromax().getPassword()
            );
            List<JsonNode> vendMachines = vendClient.getMachines(vendToken);
            vendOffline = getOfflineMachinesWithTimestamps(vendMachines, zoneId);
        } catch (IntegrationException error) {
            handleIntegrationFailure("VendProMax", error.getDetails(), previousFailingIntegrations, currentFailingIntegrations);
        } catch (Exception error) {
            log.error("Erro inesperado em VendProMax: {}", error.getMessage(), error);
            handleIntegrationFailure("VendProMax", error.getMessage(), previousFailingIntegrations, currentFailingIntegrations);
        }

        // 2. PayXYZ
        try {
            String payToken = payxyzClient.login(
                    properties.getPayxyz().getEmail(),
                    properties.getPayxyz().getPassword(),
                    true
            );
            List<JsonNode> payMachines = payxyzClient.getMachines(payToken);
            payOffline = getOfflineMachinesWithTimestamps(payMachines, zoneId);
        } catch (IntegrationException error) {
            handleIntegrationFailure("PayXYZ", error.getDetails(), previousFailingIntegrations, currentFailingIntegrations);
        } catch (Exception error) {
            log.error("Erro inesperado em PayXYZ: {}", error.getMessage(), error);
            handleIntegrationFailure("PayXYZ", error.getMessage(), previousFailingIntegrations, currentFailingIntegrations);
        }

        // 3. Zapizi
        try {
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
            zapOffline = getOfflineMachinesWithTimestamps(zapMachines, zoneId);
        } catch (IntegrationException error) {
            handleIntegrationFailure("Zapizi", error.getDetails(), previousFailingIntegrations, currentFailingIntegrations);
        } catch (Exception error) {
            log.error("Erro inesperado em Zapizi: {}", error.getMessage(), error);
            handleIntegrationFailure("Zapizi", error.getMessage(), previousFailingIntegrations, currentFailingIntegrations);
        }

        saveFileSet(failingIntegrationsFile, currentFailingIntegrations);

        Path vendOfflineFile = dataDir.resolve("vend_offline.json");
        Path payOfflineFile = dataDir.resolve("pay_offline.json");
        Path zapOfflineFile = dataDir.resolve("zap_offline.json");

        if (currentFailingIntegrations.contains("VendProMax")) {
            vendOffline = loadOfflineTimestamps(vendOfflineFile);
            log.info("Mantendo máquinas VendProMax offline anteriores devido a falha na integração: {}", vendOffline.keySet());
        } else {
            saveOfflineTimestamps(vendOfflineFile, vendOffline);
        }

        if (currentFailingIntegrations.contains("PayXYZ")) {
            payOffline = loadOfflineTimestamps(payOfflineFile);
            log.info("Mantendo máquinas PayXYZ offline anteriores devido a falha na integração: {}", payOffline.keySet());
        } else {
            saveOfflineTimestamps(payOfflineFile, payOffline);
        }

        if (currentFailingIntegrations.contains("Zapizi")) {
            zapOffline = loadOfflineTimestamps(zapOfflineFile);
            log.info("Mantendo máquinas Zapizi offline anteriores devido a falha na integração: {}", zapOffline.keySet());
        } else {
            saveOfflineTimestamps(zapOfflineFile, zapOffline);
        }

        // Se todas as integrações falharam, não há máquinas para validar
        if (currentFailingIntegrations.size() >= 3) {
            log.warn("Todas as integrações falharam no monitoramento.");
            return;
        }

        Map<String, String> currentOfflineMap = new TreeMap<>();
        currentOfflineMap.putAll(vendOffline);
        currentOfflineMap.putAll(payOffline);
        currentOfflineMap.putAll(zapOffline);

        Set<String> currentOfflineSet = new TreeSet<>(currentOfflineMap.keySet());
        List<String> currentOffline = new ArrayList<>(currentOfflineSet);

        log.info("Máquinas VendProMax offline: {}", vendOffline.keySet());
        log.info("Máquinas PayXYZ offline: {}", payOffline.keySet());
        log.info("Máquinas Zapizi offline: {}", zapOffline.keySet());
        log.info("Status anterior offline: {}", previousOffline.stream().sorted().toList());
        log.info("Status atual offline: {}", currentOffline);

        // Remove from notifiedBackOnline any machine that is currently offline
        notifiedBackOnline.removeAll(currentOfflineSet);

        // Check which machines became online
        Set<String> becameOnline = new TreeSet<>(previousOffline);
        becameOnline.removeAll(currentOfflineSet);

        // When a machine is back online, remove its offline timestamp tracking
        for (String onlineMachine : becameOnline) {
            offlineTimestamps.remove(onlineMachine);
        }

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

        // For all offline machines, ensure they have an offline timestamp assigned / updated
        String nowStr = ZonedDateTime.now(zoneId).format(DATE_TIME_FORMATTER);
        for (String machine : currentOfflineSet) {
            String candidateTs = currentOfflineMap.get(machine);
            if (candidateTs != null && !candidateTs.isBlank()) {
                // Atualiza sempre com a informação mais recente da integradora, mesmo que a máquina já estivesse offline
                offlineTimestamps.put(machine, candidateTs);
            } else if (!offlineTimestamps.containsKey(machine)) {
                offlineTimestamps.put(machine, nowStr);
            }
        }

        List<String> sections = new ArrayList<>();
        if (!newOffline.isEmpty()) {
            sections.add(buildOfflineMessage("*🔴NOVA MAQUINA OFFLINE:*", newOffline, offlineTimestamps));
        }
        if (!oldOffline.isEmpty()) {
            sections.add(buildOfflineMessage("*🔴OFFLINE:*", oldOffline, offlineTimestamps));
        }

        String offlineMessage = sections.stream()
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));

        saveFileSet(previousOfflineFile, currentOfflineSet);
        saveFileSet(notifiedBackOnlineFile, notifiedBackOnline);
        saveOfflineTimestamps(offlineTimestampsFile, offlineTimestamps);

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

    public Map<String, String> getOfflineMachinesWithTimestamps(List<JsonNode> machines, ZoneId zoneId) {
        Map<String, String> result = new LinkedHashMap<>();
        if (machines == null) {
            return result;
        }

        for (JsonNode machine : machines) {
            String status = null;
            if (machine.hasNonNull("status")) {
                status = machine.get("status").asText();
            } else if (machine.hasNonNull("statusMaquina")) {
                status = machine.get("statusMaquina").asText();
            }

            if (status != null && "OFFLINE".equalsIgnoreCase(status.trim())) {
                String name = null;
                if (machine.hasNonNull("locationName") && !machine.get("locationName").asText().isBlank()) {
                    name = machine.get("locationName").asText().trim();
                } else if (machine.hasNonNull("uniqueCode") && !machine.get("uniqueCode").asText().isBlank()) {
                    name = machine.get("uniqueCode").asText().trim();
                } else if (machine.hasNonNull("nome") && !machine.get("nome").asText().isBlank()) {
                    name = machine.get("nome").asText().trim();
                } else if (machine.hasNonNull("name") && !machine.get("name").asText().isBlank()) {
                    name = machine.get("name").asText().trim();
                }

                if (name != null && !name.isBlank()) {
                    if (name.toLowerCase().contains("sem loja")) {
                        continue;
                    }
                    String formattedDate = extractLatestTimestamp(machine, zoneId);
                    result.put(name, formattedDate);
                }
            }
        }
        return result;
    }

    public List<String> getOfflineMachineNames(List<JsonNode> machines) {
        return new ArrayList<>(getOfflineMachinesWithTimestamps(machines, ZoneId.of("America/Sao_Paulo")).keySet());
    }

    public static ZonedDateTime parseToZonedDateTime(String raw, ZoneId zoneId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(raw.trim());
            return instant.atZone(zoneId);
        } catch (Exception e) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(raw.trim());
                return ldt.atZone(zoneId);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static String extractLatestTimestamp(JsonNode machine, ZoneId zoneId) {
        if (machine == null) {
            return null;
        }

        String[] candidateFields = {
                "dataStatusMaquina",
                "lastConnection",
                "ultimaRequisicao",
                "ultimoPagamentoRecebido",
                "updatedAt",
                "lastSeen",
                "dataAtualizacao"
        };

        ZonedDateTime latest = null;

        for (String field : candidateFields) {
            if (machine.hasNonNull(field) && !machine.get(field).asText().isBlank()) {
                String raw = machine.get(field).asText().trim();
                ZonedDateTime parsed = parseToZonedDateTime(raw, zoneId);
                if (parsed != null) {
                    if (latest == null || parsed.isAfter(latest)) {
                        latest = parsed;
                    }
                }
            }
        }

        if (latest != null) {
            return DATE_TIME_FORMATTER.format(latest);
        }
        return null;
    }

    public static String parseTimestamp(String raw, ZoneId zoneId) {
        ZonedDateTime zdt = parseToZonedDateTime(raw, zoneId);
        return zdt != null ? DATE_TIME_FORMATTER.format(zdt) : null;
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

    public String buildOfflineMessage(String title, Collection<String> machineNames, Map<String, String> timestamps) {
        Comparator<String> dateComparator = (m1, m2) -> {
            String ts1 = (timestamps != null) ? timestamps.get(m1) : null;
            String ts2 = (timestamps != null) ? timestamps.get(m2) : null;

            LocalDateTime dt1 = parseFormattedDate(ts1);
            LocalDateTime dt2 = parseFormattedDate(ts2);

            if (dt1 != null && dt2 != null) {
                int cmp = dt1.compareTo(dt2);
                if (cmp != 0) {
                    return cmp;
                }
            } else if (dt1 != null) {
                return -1; // Com data conhecida tem prioridade sobre desconhecida
            } else if (dt2 != null) {
                return 1;
            }
            return m1.compareToIgnoreCase(m2);
        };

        List<String> names = machineNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted(dateComparator)
                .toList();

        if (names.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add(title);
        for (String name : names) {
            String since = (timestamps != null) ? timestamps.get(name) : null;
            if (since != null && !since.isBlank()) {
                lines.add("• " + name + " - " + since);
            } else {
                lines.add("• " + name);
            }
        }
        return String.join("\n", lines);
    }

    private static LocalDateTime parseFormattedDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr.trim(), DATE_TIME_FORMATTER);
        } catch (Exception e) {
            return null;
        }
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

    private void handleIntegrationFailure(
            String serviceName,
            String details,
            Set<String> previousFailing,
            Set<String> currentFailing
    ) {
        currentFailing.add(serviceName);
        if (!previousFailing.contains(serviceName)) {
            log.warn("Integração falhou pela primeira vez: {}. Enviando alerta.", serviceName);
            sendIntegrationError(serviceName, details);
        } else {
            log.info("Integração {} continua com falha. Alerta repetido suprimido.", serviceName);
        }
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

    public Map<String, String> loadOfflineTimestamps(Path filepath) {
        if (!Files.exists(filepath)) {
            return new TreeMap<>();
        }
        try {
            return objectMapper.readValue(filepath.toFile(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("Erro ao ler {}: {}", filepath, e.getMessage());
            return new TreeMap<>();
        }
    }

    public void saveOfflineTimestamps(Path filepath, Map<String, String> timestamps) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filepath.toFile(), timestamps);
        } catch (Exception e) {
            log.error("Erro ao salvar {}: {}", filepath, e.getMessage());
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

    public Map<String, Object> getStatusAtual() {
        Path dataDir = resolveDataDirectory();
        Path previousOfflineFile = dataDir.resolve("offline_status.txt");
        Path offlineTimestampsFile = dataDir.resolve("offline_timestamps.json");
        Path failingIntegrationsFile = dataDir.resolve("failing_integrations.txt");
        Path previousMessageFile = dataDir.resolve("last_offline_notification.txt");

        Set<String> offline = loadFileSet(previousOfflineFile);
        offline.removeIf(m -> m.toLowerCase().contains("sem loja"));
        Map<String, String> timestamps = loadOfflineTimestamps(offlineTimestampsFile);
        Set<String> failing = loadFileSet(failingIntegrationsFile);
        String lastNotification = loadFileText(previousMessageFile);

        List<Map<String, String>> maquinasList = new ArrayList<>();
        for (String m : offline) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("nome", m);
            item.put("offlineDesde", timestamps.getOrDefault(m, "Desconhecido"));
            maquinasList.add(item);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalOffline", offline.size());
        status.put("maquinasOffline", maquinasList);
        status.put("failingIntegrations", new ArrayList<>(failing));
        status.put("ultimaNotificacao", lastNotification);
        return status;
    }
}
