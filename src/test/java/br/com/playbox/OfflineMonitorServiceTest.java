package br.com.playbox;

import br.com.playbox.config.AppProperties;
import br.com.playbox.service.OfflineMonitorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OfflineMonitorServiceTest {

    private OfflineMonitorService monitorService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        objectMapper = new ObjectMapper();
        monitorService = new OfflineMonitorService(null, null, null, properties, objectMapper);
    }

    @Test
    void testBuildMessage() {
        List<String> names = List.of("Lan House - Custódia", "Casa Nobre - Sertania");
        String message = monitorService.buildMessage("*🔴NOVA MAQUINA OFFLINE:*", names);

        String expected = "*🔴NOVA MAQUINA OFFLINE:*\n• Casa Nobre - Sertania\n• Lan House - Custódia";
        assertEquals(expected, message);
    }

    @Test
    void testBuildOfflineMessageWithTimestamps() {
        List<String> names = List.of("Casa Nobre - Sertania", "Lan House - Custódia", "Menininho");
        Map<String, String> timestamps = Map.of(
                "Casa Nobre - Sertania", "15/10/2026 10:30",
                "Lan House - Custódia", "14/10/2026 21:00",
                "Menininho", "12/10/2026 08:15"
        );
        String message = monitorService.buildOfflineMessage("*🔴OFFLINE:*", names, timestamps);

        // Menininho (12/10) < Lan House (14/10) < Casa Nobre (15/10)
        String expected = "*🔴OFFLINE:*\n• Menininho - 12/10/2026 08:15\n• Lan House - Custódia - 14/10/2026 21:00\n• Casa Nobre - Sertania - 15/10/2026 10:30";
        assertEquals(expected, message);
    }

    @Test
    void testParseTimestamp() {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        // UTC instant
        String formatted = OfflineMonitorService.parseTimestamp("2026-10-15T00:18:03.656Z", zoneId);
        assertEquals("14/10/2026 21:18", formatted);

        assertNull(OfflineMonitorService.parseTimestamp(null, zoneId));
        assertNull(OfflineMonitorService.parseTimestamp("", zoneId));
    }

    @Test
    void testGetOfflineMachineNames() {
        ArrayNode machines = objectMapper.createArrayNode();

        ObjectNode m1 = objectMapper.createObjectNode();
        m1.put("nome", "Casa Nobre");
        m1.put("status", "OFFLINE");
        m1.put("dataStatusMaquina", "2026-10-15T00:18:03.656Z");
        machines.add(m1);

        ObjectNode m2 = objectMapper.createObjectNode();
        m2.put("nome", "Posto Iguaracy");
        m2.put("status", "ONLINE");
        machines.add(m2);

        ObjectNode m3 = objectMapper.createObjectNode();
        m3.put("nome", "Time - BR Arena");
        m3.put("statusMaquina", "OFFLINE");
        m3.put("lastConnection", "2026-09-28T18:46:31.641Z");
        machines.add(m3);

        List<String> offline = monitorService.getOfflineMachineNames(List.of(m1, m2, m3));

        assertEquals(2, offline.size());
        assertTrue(offline.contains("Casa Nobre"));
        assertTrue(offline.contains("Time - BR Arena"));
        assertFalse(offline.contains("Posto Iguaracy"));

        Map<String, String> mapWithTimestamps = monitorService.getOfflineMachinesWithTimestamps(List.of(m1, m2, m3), ZoneId.of("America/Sao_Paulo"));
        assertEquals("14/10/2026 21:18", mapWithTimestamps.get("Casa Nobre"));
        assertEquals("28/09/2026 15:46", mapWithTimestamps.get("Time - BR Arena"));
    }

    @Test
    void testIgnoreSemLojaMachines() {
        ArrayNode machines = objectMapper.createArrayNode();

        ObjectNode m1 = objectMapper.createObjectNode();
        m1.put("nome", "Sem Loja");
        m1.put("status", "OFFLINE");
        m1.put("dataStatusMaquina", "2026-10-15T00:18:03.656Z");
        machines.add(m1);

        ObjectNode m2 = objectMapper.createObjectNode();
        m2.put("locationName", "Totem Sem Loja SP");
        m2.put("status", "OFFLINE");
        machines.add(m2);

        ObjectNode m3 = objectMapper.createObjectNode();
        m3.put("nome", "Casa Nobre");
        m3.put("status", "OFFLINE");
        machines.add(m3);

        List<String> offline = monitorService.getOfflineMachineNames(List.of(m1, m2, m3));
        assertEquals(1, offline.size());
        assertEquals("Casa Nobre", offline.get(0));
    }

    @Test
    void testExtractLatestTimestampSelectsMostRecent() {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ObjectNode machine = objectMapper.createObjectNode();
        machine.put("nome", "Praça do Sabor");
        machine.put("status", "OFFLINE");
        // payment at 2026-10-21T02:32:10Z (20/10 23:32 UTC-3)
        machine.put("ultimoPagamentoRecebido", "2026-10-21T02:32:10.659Z");
        // request at 2026-10-21T03:42:38Z (21/10 00:42 UTC-3) - newer
        machine.put("ultimaRequisicao", "2026-10-21T03:42:38.396Z");

        String latest = OfflineMonitorService.extractLatestTimestamp(machine, zoneId);
        assertEquals("21/10/2026 00:42", latest);

        // Now test if lastConnection is even newer
        machine.put("lastConnection", "2026-10-21T15:00:00.000Z"); // 21/10 12:00 UTC-3
        String evenNewer = OfflineMonitorService.extractLatestTimestamp(machine, zoneId);
        assertEquals("21/10/2026 12:00", evenNewer);
    }

    @Test
    void testMonitorsOnlyActiveMachinesWithStores() {
        br.com.playbox.repository.MaquinaRepository mockRepo = org.mockito.Mockito.mock(br.com.playbox.repository.MaquinaRepository.class);
        AppProperties properties = new AppProperties();
        OfflineMonitorService serviceWithRepo = new OfflineMonitorService(null, null, null, properties, objectMapper, mockRepo);

        br.com.playbox.entity.Estabelecimento est = new br.com.playbox.entity.Estabelecimento();
        est.setId(10L);
        est.setNome("Shopping Plaza");
        est.setCidade("Recife");

        // Machine 1: Active with store
        br.com.playbox.entity.Maquina mActive = new br.com.playbox.entity.Maquina();
        mActive.setId(1L);
        mActive.setIntegracao("ZAPIZI");
        mActive.setExternalId("POSX-111");
        mActive.setNomeIntegracao("Chaves");
        mActive.setAtivo(true);
        mActive.setEstabelecimento(est);

        // Machine 2: Inactive with store
        br.com.playbox.entity.Maquina mInactive = new br.com.playbox.entity.Maquina();
        mInactive.setId(2L);
        mInactive.setIntegracao("ZAPIZI");
        mInactive.setExternalId("POSX-222");
        mInactive.setNomeIntegracao("Minions");
        mInactive.setAtivo(false);
        mInactive.setEstabelecimento(est);

        // Machine 3: Active without store (Sem Loja / Depósito)
        br.com.playbox.entity.Maquina mNoStore = new br.com.playbox.entity.Maquina();
        mNoStore.setId(3L);
        mNoStore.setIntegracao("ZAPIZI");
        mNoStore.setExternalId("POSX-333");
        mNoStore.setNomeIntegracao("Reserva");
        mNoStore.setAtivo(true);
        mNoStore.setEstabelecimento(null);

        org.mockito.Mockito.when(mockRepo.findByIntegracaoAndExternalId("ZAPIZI", "POSX-111"))
                .thenReturn(java.util.Optional.of(mActive));
        org.mockito.Mockito.when(mockRepo.findByIntegracaoAndExternalId("ZAPIZI", "POSX-222"))
                .thenReturn(java.util.Optional.of(mInactive));
        org.mockito.Mockito.when(mockRepo.findByIntegracaoAndExternalId("ZAPIZI", "POSX-333"))
                .thenReturn(java.util.Optional.of(mNoStore));

        ObjectNode node1 = objectMapper.createObjectNode();
        node1.put("uniqueCode", "POSX-111");
        node1.put("locationName", "Chaves");
        node1.put("status", "OFFLINE");

        ObjectNode node2 = objectMapper.createObjectNode();
        node2.put("uniqueCode", "POSX-222");
        node2.put("locationName", "Minions");
        node2.put("status", "OFFLINE");

        ObjectNode node3 = objectMapper.createObjectNode();
        node3.put("uniqueCode", "POSX-333");
        node3.put("locationName", "Reserva");
        node3.put("status", "OFFLINE");

        Map<String, String> result = serviceWithRepo.getOfflineMachinesWithTimestamps(
                List.of(node1, node2, node3),
                ZoneId.of("America/Sao_Paulo"),
                "ZAPIZI"
        );

        // Only node1 (active with store) should be monitored!
        // Como o estabelecimento 'Shopping Plaza' tem apenas 1 máquina ativa, o nome exibido deve ser o do estabelecimento!
        assertEquals(1, result.size());
        assertTrue(result.containsKey("Shopping Plaza"));
        assertFalse(result.containsKey("Minions")); // Inactive
        assertFalse(result.containsKey("Reserva")); // Sem Loja
    }

    @Test
    void testResolverNomeExibicaoUmaMaquinaVsMultiplas() {
        br.com.playbox.repository.MaquinaRepository mockRepo = org.mockito.Mockito.mock(br.com.playbox.repository.MaquinaRepository.class);
        AppProperties properties = new AppProperties();
        OfflineMonitorService service = new OfflineMonitorService(null, null, null, properties, objectMapper, mockRepo);

        br.com.playbox.entity.Estabelecimento est1 = new br.com.playbox.entity.Estabelecimento();
        est1.setId(1L);
        est1.setNome("Casa Nobre");

        br.com.playbox.entity.Maquina m1 = new br.com.playbox.entity.Maquina();
        m1.setId(10L);
        m1.setNomeIntegracao("Peixinho2");
        m1.setAtivo(true);
        m1.setEstabelecimento(est1);

        // Cenário 1: Apenas 1 máquina no estabelecimento -> usa nome do estabelecimento
        org.mockito.Mockito.when(mockRepo.findByEstabelecimentoId(1L)).thenReturn(List.of(m1));
        String nome1 = service.resolverNomeExibicao(m1, "Peixinho2");
        assertEquals("Casa Nobre", nome1);

        // Cenário 2: Mais de uma máquina no estabelecimento -> usa nome da máquina
        br.com.playbox.entity.Maquina m2 = new br.com.playbox.entity.Maquina();
        m2.setId(11L);
        m2.setNomeIntegracao("Peixinho1");
        m2.setAtivo(true);
        m2.setEstabelecimento(est1);

        org.mockito.Mockito.when(mockRepo.findByEstabelecimentoId(1L)).thenReturn(List.of(m1, m2));
        String nomeComMultiplas1 = service.resolverNomeExibicao(m1, "Peixinho2");
        String nomeComMultiplas2 = service.resolverNomeExibicao(m2, "Peixinho1");
        assertEquals("Peixinho2", nomeComMultiplas1);
        assertEquals("Peixinho1", nomeComMultiplas2);
    }

    @Test
    void testMigrateLegacyOfflineNames() {
        br.com.playbox.repository.MaquinaRepository mockRepo = org.mockito.Mockito.mock(br.com.playbox.repository.MaquinaRepository.class);
        AppProperties properties = new AppProperties();
        OfflineMonitorService service = new OfflineMonitorService(null, null, null, properties, objectMapper, mockRepo);

        br.com.playbox.entity.Estabelecimento est = new br.com.playbox.entity.Estabelecimento();
        est.setId(7L);
        est.setNome("Casa Nobre");

        br.com.playbox.entity.Maquina m = new br.com.playbox.entity.Maquina();
        m.setId(2L);
        m.setNomeIntegracao("Peixinho2");
        m.setAtivo(true);
        m.setEstabelecimento(est);

        org.mockito.Mockito.when(mockRepo.findByAtivoTrue()).thenReturn(List.of(m));
        org.mockito.Mockito.when(mockRepo.findByEstabelecimentoId(7L)).thenReturn(List.of(m));

        java.util.Set<String> previousOffline = new java.util.TreeSet<>(List.of("Casa Nobre - Sertania"));
        java.util.Map<String, String> timestamps = new java.util.TreeMap<>(Map.of("Casa Nobre - Sertania", "21/10/2026 14:09"));

        service.migrateLegacyOfflineNames(previousOffline, timestamps);

        assertTrue(previousOffline.contains("Casa Nobre"));
        assertFalse(previousOffline.contains("Casa Nobre - Sertania"));
        assertEquals("21/10/2026 14:09", timestamps.get("Casa Nobre"));
        assertNull(timestamps.get("Casa Nobre - Sertania"));
    }
}
