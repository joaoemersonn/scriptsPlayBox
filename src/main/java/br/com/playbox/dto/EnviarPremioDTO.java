package br.com.playbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados para registro de premiação/saída de pelúcia (sensor óptico 12V) no módulo PlayBox")
public class EnviarPremioDTO {

    @Schema(
            description = "Quantidade de pelúcias entregues / liberadas pela máquina",
            example = "1",
            defaultValue = "1"
    )
    private Integer quantidade = 1;

    @Schema(
            description = "Identificador único ou código de rastreamento do prêmio (opcional)",
            example = "PREMIO-20261030-001"
    )
    private String txId;

    public EnviarPremioDTO() {
    }

    public EnviarPremioDTO(Integer quantidade, String txId) {
        this.quantidade = quantidade;
        this.txId = txId;
    }

    public Integer getQuantidade() {
        return (quantidade != null && quantidade > 0) ? quantidade : 1;
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
