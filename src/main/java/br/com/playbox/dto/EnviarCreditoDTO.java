package br.com.playbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados para liberação de crédito/pulso no moedeiro da máquina via módulo PlayBox")
public class EnviarCreditoDTO {

    @Schema(
            description = "Quantidade de pulsos de crédito a liberar no moedeiro",
            example = "2",
            defaultValue = "1"
    )
    private Integer quantidade = 1;

    @Schema(
            description = "Identificador único da transação (Pix, Cartão de Crédito, POS ou Gateway de Pagamento). Se omitido, será gerado automaticamente.",
            example = "PAY-20261030-98421"
    )
    private String txId;

    public EnviarCreditoDTO() {
    }

    public EnviarCreditoDTO(Integer quantidade, String txId) {
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
