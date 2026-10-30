package br.com.playbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resposta do envio de comando para o módulo PlayBox via MQTT")
public class ComandoRespostaDTO {

    @Schema(description = "Status do envio do comando", example = "SUCCESS")
    private String status;

    @Schema(description = "Mensagem descritiva do resultado", example = "Comando de 2 crédito(s) enviado com sucesso ao broker para o módulo PB-00001")
    private String mensagem;

    @Schema(description = "Identificador do módulo na máquina", example = "PB-00001")
    private String deviceId;

    @Schema(description = "Quantidade de créditos enviados", example = "2")
    private Integer quantidade;

    @Schema(description = "ID único da transação", example = "PAY-20261030-98421")
    private String txId;

    public ComandoRespostaDTO() {
    }

    public ComandoRespostaDTO(String status, String mensagem, String deviceId, Integer quantidade, String txId) {
        this.status = status;
        this.mensagem = mensagem;
        this.deviceId = deviceId;
        this.quantidade = quantidade;
        this.txId = txId;
    }

    public static ComandoRespostaDTO sucesso(String mensagem, String deviceId, Integer quantidade, String txId) {
        return new ComandoRespostaDTO("SUCCESS", mensagem, deviceId, quantidade, txId);
    }

    public static ComandoRespostaDTO erro(String mensagem, String deviceId) {
        return new ComandoRespostaDTO("ERROR", mensagem, deviceId, null, null);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMensagem() {
        return mensagem;
    }

    public void setMensagem(String mensagem) {
        this.mensagem = mensagem;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }
}
