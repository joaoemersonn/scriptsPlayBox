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
        List<String> names = List.of("Lan House - Custódia", "Casa Nobre - Sertania");
        Map<String, String> timestamps = Map.of(
                "Casa Nobre - Sertania", "15/10/2026 10:30",
                "Lan House - Custódia", "14/10/2026 21:00"
        );
        String message = monitorService.buildOfflineMessage("*🔴OFFLINE:*", names, timestamps);

        String expected = "*🔴OFFLINE:*\n• Casa Nobre - Sertania - 15/10/2026 10:30\n• Lan House - Custódia - 14/10/2026 21:00";
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
}
