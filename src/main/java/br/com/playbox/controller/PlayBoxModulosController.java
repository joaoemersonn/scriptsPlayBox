package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.service.PlayBoxMqttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping({"/api/v1/playbox-modulos", "/api/v1/playbox-iot"})
@Tag(name = "PlayBox Módulos", description = "Gerenciamento de módulos PlayBox, controle de pulsos MQTT e telemetria em tempo real")
public class PlayBoxModulosController {

    private final PlayBoxMqttService mqttService;
    private final MaquinaRepository maquinaRepository;

    public PlayBoxModulosController(PlayBoxMqttService mqttService, MaquinaRepository maquinaRepository) {
        this.mqttService = mqttService;
        this.maquinaRepository = maquinaRepository;
    }

    @GetMapping("/status")
    @Operation(
            summary = "Status da Conexão com o Broker MQTT",
            description = "Retorna os detalhes de conexão do backend com o broker MQTT (Host, Porta, Tópicos e estado da conexão).",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<Map<String, Object>> getMqttStatus() {
        Map<String, Object> status = new LinkedHashMap<>(mqttService.getStatusInfo());
        List<Maquina> modulosPlayBox = maquinaRepository.findByIntegracao("PLAYBOX");
        status.put("totalModulosPlayBox", modulosPlayBox.size());
        status.put("modulosOnline", modulosPlayBox.stream().filter(m -> Boolean.TRUE.equals(m.getOnline())).count());
        return ResponseEntity.ok(status);
    }

    @GetMapping({"/modulos", "/maquinas"})
    @Operation(
            summary = "Listar Módulos PlayBox",
            description = "Retorna a lista de módulos operando com a integração nativa PlayBox, incluindo IP, sinal Wi-Fi e odômetros de 12V.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<List<Maquina>> listarModulosPlayBox() {
        List<Maquina> list = maquinaRepository.findByIntegracao("PLAYBOX");
        return ResponseEntity.ok(list);
    }

    @PostMapping({"/modulos/{deviceId}/credito", "/maquinas/{deviceId}/credito"})
    @Operation(
            summary = "Liberar Crédito Remoto no Módulo (Pix/Cartão/Manual)",
            description = "Envia via MQTT um comando de injeção de pulsos de crédito no moedeiro do módulo selecionado.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<Map<String, Object>> enviarCredito(
            @PathVariable @Parameter(description = "ID do módulo (ex: PB-00001)") String deviceId,
            @RequestBody(required = false) Map<String, Object> payload
    ) {
        int quantidade = 1;
        String txId = "TX-API-" + UUID.randomUUID().toString().substring(0, 8);

        if (payload != null) {
            if (payload.containsKey("quantidade")) {
                quantidade = Integer.parseInt(payload.get("quantidade").toString());
            } else if (payload.containsKey("qty")) {
                quantidade = Integer.parseInt(payload.get("qty").toString());
            }
            if (payload.containsKey("tx_id")) {
                txId = payload.get("tx_id").toString();
            } else if (payload.containsKey("txId")) {
                txId = payload.get("txId").toString();
            }
        }

        boolean publicado = mqttService.enviarPulsoCredito(deviceId, quantidade, txId);
        if (publicado) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "mensagem", "Comando de " + quantidade + " crédito(s) enviado com sucesso ao broker para o módulo " + deviceId,
                    "deviceId", deviceId,
                    "quantidade", quantidade,
                    "txId", txId
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "ERROR",
                    "mensagem", "Não foi possível enviar comando: cliente MQTT desconectado do broker.",
                    "deviceId", deviceId
            ));
        }
    }

    @PostMapping({"/modulos/{deviceId}/reset", "/maquinas/{deviceId}/reset"})
    @Operation(
            summary = "Reiniciar / Resetar Configurações do Módulo",
            description = "Envia comando MQTT para o módulo limpar credenciais de Wi-Fi e retornar ao Modo Portal AP.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<Map<String, Object>> resetarModulo(
            @PathVariable @Parameter(description = "ID do módulo (ex: PB-00001)") String deviceId
    ) {
        boolean publicado = mqttService.enviarResetRemoto(deviceId);
        if (publicado) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "mensagem", "Comando de reset enviado para o módulo " + deviceId
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "ERROR",
                    "mensagem", "Cliente MQTT desconectado do broker."
            ));
        }
    }

    @PostMapping({"/modulos/{deviceId}/ota", "/maquinas/{deviceId}/ota"})
    @Operation(
            summary = "Atualização de Firmware Remota (OTA) do Módulo",
            description = "Envia comando para o módulo baixar e gravar firmware remotamente a partir de uma URL binária.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    public ResponseEntity<Map<String, Object>> atualizarFirmwareRemoto(
            @PathVariable @Parameter(description = "ID do módulo (ex: PB-00001)") String deviceId,
            @RequestBody Map<String, String> body
    ) {
        String url = body != null ? body.get("url") : null;
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "mensagem", "O campo 'url' com o link do binário é obrigatório."
            ));
        }

        boolean publicado = mqttService.enviarComandoOTA(deviceId, url.trim());
        if (publicado) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "mensagem", "Comando OTA enviado para o módulo " + deviceId,
                    "url", url
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "ERROR",
                    "mensagem", "Cliente MQTT desconectado do broker."
            ));
        }
    }
}
