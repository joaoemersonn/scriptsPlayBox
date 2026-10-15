package br.com.playbox;

import br.com.playbox.config.AppProperties;
import br.com.playbox.service.OfflineMonitorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineMonitorServiceTest {

    private OfflineMonitorService monitorService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        monitorService = new OfflineMonitorService(null, null, null, properties);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testBuildMessage() {
        List<String> names = List.of("Lan House - Custódia", "Casa Nobre - Sertania");
        String message = monitorService.buildMessage("*🔴NOVA MAQUINA OFFLINE:*", names);

        String expected = "*🔴NOVA MAQUINA OFFLINE:*\n• Casa Nobre - Sertania\n• Lan House - Custódia";
        assertEquals(expected, message);
    }

    @Test
    void testGetOfflineMachineNames() {
        ArrayNode machines = objectMapper.createArrayNode();

        ObjectNode m1 = objectMapper.createObjectNode();
        m1.put("nome", "Casa Nobre");
        m1.put("status", "OFFLINE");
        machines.add(m1);

        ObjectNode m2 = objectMapper.createObjectNode();
        m2.put("nome", "Posto Iguaracy");
        m2.put("status", "ONLINE");
        machines.add(m2);

        ObjectNode m3 = objectMapper.createObjectNode();
        m3.put("nome", "Time - BR Arena");
        m3.put("statusMaquina", "OFFLINE");
        machines.add(m3);

        List<String> offline = monitorService.getOfflineMachineNames(List.of(m1, m2, m3));

        assertEquals(2, offline.size());
        assertTrue(offline.contains("Casa Nobre"));
        assertTrue(offline.contains("Time - BR Arena"));
        assertFalse(offline.contains("Posto Iguaracy"));
    }
}

