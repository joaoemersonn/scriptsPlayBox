package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.dto.*;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.service.PlayBoxMqttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/playbox-modulos")
@Tag(name = "PlayBox Módulos", description = "Gerenciamento de módulos PlayBox, injeção de créditos, registro de notas/fichas (espécie), saída de pelúcias e telemetria em tempo real")
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status do broker retornado com sucesso",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"enabled\":true,\"brokerUrl\":\"tcp://broker.hivemq.com:1883\",\"brokerHost\":\"broker.hivemq.com\",\"brokerPort\":1883,\"baseTopic\":\"playbox/maquinas\",\"connected\":true,\"connecting\":false,\"clientId\":\"playbox-backend-server\",\"totalModulosPlayBox\":1,\"modulosOnline\":1}")))
    })
    public ResponseEntity<Map<String, Object>> getMqttStatus() {
        Map<String, Object> status = new LinkedHashMap<>(mqttService.getStatusInfo());
        List<Maquina> modulosPlayBox = maquinaRepository.findByIntegracao("PLAYBOX");
        status.put("totalModulosPlayBox", modulosPlayBox.size());
        status.put("modulosOnline", modulosPlayBox.stream().filter(m -> Boolean.TRUE.equals(m.getOnline())).count());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/modulos")
    @Operation(
            summary = "Listar Módulos PlayBox",
            description = "Retorna a lista de módulos operando com a integração nativa PlayBox, incluindo IP, sinal Wi-Fi e odômetros de 12V.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de módulos cadastrados e ativos")
    })
    public ResponseEntity<List<Maquina>> listarModulosPlayBox() {
        List<Maquina> list = maquinaRepository.findByIntegracao("PLAYBOX");
        return ResponseEntity.ok(list);
    }

    @PostMapping("/modulos/{deviceId}/credito")
    @Operation(
            summary = "Liberar Crédito Remoto no Módulo (Crédito Digital / Pix / Cartão)",
            description = "Registra a transação digital no sistema e envia via MQTT um comando de injeção de pulsos de crédito no moedeiro do módulo selecionado.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Crédito registrado e comando enviado ao broker com sucesso",
                    content = @Content(schema = @Schema(implementation = ComandoRespostaDTO.class),
                            examples = @ExampleObject(value = "{\"status\":\"SUCCESS\",\"mensagem\":\"Comando de 2 crédito(s) enviado com sucesso ao broker para o módulo PB-00001\",\"deviceId\":\"PB-00001\",\"quantidade\":2,\"txId\":\"PAY-20261030-98421\"}"))),
            @ApiResponse(responseCode = "503", description = "Transação gravada, porém broker MQTT desconectado ou indisponível")
    })
    public ResponseEntity<ComandoRespostaDTO> enviarCredito(
            @PathVariable @Parameter(description = "Identificador do módulo na máquina", example = "PB-00001") String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Dados para injeção de créditos (quantidade, valor opcional e ID do pagamento)",
                    content = @Content(
                            schema = @Schema(implementation = EnviarCreditoDTO.class),
                            examples = @ExampleObject(
                                    name = "Exemplo Real",
                                    value = "{\"quantidade\": 2, \"valor\": 10.00, \"txId\": \"PAY-20261030-98421\"}"
                            )
                    )
            )
            @RequestBody(required = false) EnviarCreditoDTO payload
    ) {
        int quantidade = (payload != null && payload.getQuantidade() != null) ? payload.getQuantidade() : 1;
        Double valor = (payload != null) ? payload.getValor() : null;
        String txId = (payload != null && payload.getTxId() != null && !payload.getTxId().isBlank())
                ? payload.getTxId().trim()
                : ("TX-PLAYBOX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // 1. Grava imediatamente a transação para o módulo PlayBox no banco de dados
        mqttService.registrarTransacaoCreditoDigital(deviceId, txId, quantidade, valor);

        // 2. Envia o pulso elétrico via broker MQTT
        boolean publicado = mqttService.enviarPulsoCredito(deviceId, quantidade, txId);
        if (publicado) {
            return ResponseEntity.ok(ComandoRespostaDTO.sucesso(
                    "Comando de " + quantidade + " crédito(s) enviado com sucesso ao broker para o módulo " + deviceId,
                    deviceId,
                    quantidade,
                    txId
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ComandoRespostaDTO.erro(
                    "Transação " + txId + " gravada no banco de dados, mas cliente MQTT está desconectado do broker.",
                    deviceId
            ));
        }
    }

    @PostMapping("/modulos/{deviceId}/especie")
    @Operation(
            summary = "Registrar Entrada em Espécie (Dinheiro Físico / Noteiro / Moedeiro 12V)",
            description = "Registra entrada em dinheiro ou ficha mecânica no módulo PlayBox, gravando a transação financeira em espécie.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Entrada em espécie gravada com sucesso",
                    content = @Content(schema = @Schema(implementation = ComandoRespostaDTO.class),
                            examples = @ExampleObject(value = "{\"status\":\"SUCCESS\",\"mensagem\":\"Entrada em espécie de 1 pulso(s) (R$ 5.0) registrada com sucesso!\",\"deviceId\":\"PB-00001\",\"quantidade\":1,\"txId\":\"CASH-PB-00001-001\"}")))
    })
    public ResponseEntity<ComandoRespostaDTO> registrarEspecie(
            @PathVariable @Parameter(description = "Identificador do módulo na máquina", example = "PB-00001") String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Dados da entrada mecânica em dinheiro (pulsos e valor)",
                    content = @Content(
                            schema = @Schema(implementation = EnviarEspecieDTO.class),
                            examples = @ExampleObject(
                                    name = "Exemplo Real",
                                    value = "{\"pulsos\": 1, \"valor\": 5.00, \"txId\": \"CASH-PB-00001-001\"}"
                            )
                    )
            )
            @RequestBody(required = false) EnviarEspecieDTO payload
    ) {
        int pulsos = (payload != null && payload.getPulsos() != null) ? payload.getPulsos() : 1;
        Double valor = (payload != null) ? payload.getValor() : null;
        String txId = (payload != null && payload.getTxId() != null && !payload.getTxId().isBlank())
                ? payload.getTxId().trim()
                : ("CASH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        Transacao tx = mqttService.registrarTransacaoEspecieManual(deviceId, pulsos, valor, txId);
        return ResponseEntity.ok(ComandoRespostaDTO.sucesso(
                "Entrada em espécie de " + pulsos + " pulso(s) (R$ " + tx.getValor() + ") registrada com sucesso!",
                deviceId,
                pulsos,
                tx.getExternalTxId()
        ));
    }

    @PostMapping("/modulos/{deviceId}/premio")
    @Operation(
            summary = "Registrar Saída de Pelúcia / Prêmio Ganho (Sensor Óptico 12V)",
            description = "Registra a saída de uma ou mais pelúcias ganhas, gerando a transação de premiação e abatendo automaticamente o estoque da máquina.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saída de pelúcia e estoque atualizados com sucesso",
                    content = @Content(schema = @Schema(implementation = ComandoRespostaDTO.class),
                            examples = @ExampleObject(value = "{\"status\":\"SUCCESS\",\"mensagem\":\"Saída de 1 pelúcia(s) registrada e estoque atualizado com sucesso!\",\"deviceId\":\"PB-00001\",\"quantidade\":1,\"txId\":\"PREMIO-PB-00001-001\"}")))
    })
    public ResponseEntity<ComandoRespostaDTO> registrarPremio(
            @PathVariable @Parameter(description = "Identificador do módulo na máquina", example = "PB-00001") String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Dados da saída de pelúcia (quantidade liberada)",
                    content = @Content(
                            schema = @Schema(implementation = EnviarPremioDTO.class),
                            examples = @ExampleObject(
                                    name = "Exemplo Real",
                                    value = "{\"quantidade\": 1, \"txId\": \"PREMIO-PB-00001-001\"}"
                            )
                    )
            )
            @RequestBody(required = false) EnviarPremioDTO payload
    ) {
        int quantidade = (payload != null && payload.getQuantidade() != null) ? payload.getQuantidade() : 1;
        String txId = (payload != null && payload.getTxId() != null && !payload.getTxId().isBlank())
                ? payload.getTxId().trim()
                : ("PREMIO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        Transacao tx = mqttService.registrarSaidaPeluciaManual(deviceId, quantidade, txId);
        return ResponseEntity.ok(ComandoRespostaDTO.sucesso(
                "Saída de " + quantidade + " pelúcia(s) registrada e estoque atualizado com sucesso!",
                deviceId,
                quantidade,
                tx.getExternalTxId()
        ));
    }

    @PostMapping("/modulos/{deviceId}/reset")
    @Operation(
            summary = "Reiniciar / Resetar Configurações do Módulo",
            description = "Envia comando MQTT para o módulo limpar credenciais de Wi-Fi e retornar ao Modo Portal AP.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comando de reset enviado com sucesso",
                    content = @Content(schema = @Schema(implementation = ComandoRespostaDTO.class))),
            @ApiResponse(responseCode = "503", description = "Broker MQTT desconectado")
    })
    public ResponseEntity<ComandoRespostaDTO> resetarModulo(
            @PathVariable @Parameter(description = "Identificador do módulo na máquina", example = "PB-00001") String deviceId
    ) {
        boolean publicado = mqttService.enviarResetRemoto(deviceId);
        if (publicado) {
            return ResponseEntity.ok(ComandoRespostaDTO.sucesso(
                    "Comando de reset enviado para o módulo " + deviceId,
                    deviceId,
                    0,
                    null
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ComandoRespostaDTO.erro(
                    "Cliente MQTT desconectado do broker.",
                    deviceId
            ));
        }
    }

    @PostMapping("/modulos/{deviceId}/ota")
    @Operation(
            summary = "Atualização de Firmware Remota (OTA) do Módulo",
            description = "Envia comando para o módulo baixar e gravar novo firmware remotamente a partir de uma URL binária.",
            security = @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comando OTA enviado com sucesso",
                    content = @Content(schema = @Schema(implementation = ComandoRespostaDTO.class))),
            @ApiResponse(responseCode = "400", description = "URL do binário não fornecida ou inválida"),
            @ApiResponse(responseCode = "503", description = "Broker MQTT desconectado")
    })
    public ResponseEntity<ComandoRespostaDTO> atualizarFirmwareRemoto(
            @PathVariable @Parameter(description = "Identificador do módulo na máquina", example = "PB-00001") String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "URL do binário compilado",
                    content = @Content(
                            schema = @Schema(implementation = AtualizarFirmwareDTO.class),
                            examples = @ExampleObject(
                                    name = "Exemplo Real",
                                    value = "{\"url\": \"http://192.168.1.83:8080/firmware.bin\"}"
                            )
                    )
            )
            @RequestBody AtualizarFirmwareDTO body
    ) {
        String url = body != null ? body.getUrl() : null;
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(ComandoRespostaDTO.erro(
                    "O campo 'url' com o link do binário é obrigatório.",
                    deviceId
            ));
        }

        boolean publicado = mqttService.enviarComandoOTA(deviceId, url.trim());
        if (publicado) {
            return ResponseEntity.ok(ComandoRespostaDTO.sucesso(
                    "Comando OTA enviado para o módulo " + deviceId,
                    deviceId,
                    0,
                    url
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ComandoRespostaDTO.erro(
                    "Cliente MQTT desconectado do broker.",
                    deviceId
            ));
        }
    }
}
