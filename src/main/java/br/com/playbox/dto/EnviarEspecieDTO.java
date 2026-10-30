package br.com.playbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Dados para registro de entrada em espécie (dinheiro físico / moedeiro 12V) no módulo PlayBox")
public class EnviarEspecieDTO {

    @Schema(
            description = "Quantidade de pulsos de moedeiro mecânico ou cédulas inseridas",
            example = "1",
            defaultValue = "1"
    )
    private Integer pulsos = 1;

    @Schema(
            description = "Valor total da entrada em Reais (opcional). Se omitido, calcula pulsos * 5,00.",
            example = "5.00"
    )
    private Double valor;

    @Schema(
            description = "Identificador único ou código de referência da transação (opcional)",
            example = "CASH-20261030-001"
    )
    private String txId;

    public EnviarEspecieDTO() {
    }

    public EnviarEspecieDTO(Integer pulsos, Double valor, String txId) {
        this.pulsos = pulsos;
        this.valor = valor;
        this.txId = txId;
    }

    public Integer getPulsos() {
        return (pulsos != null && pulsos > 0) ? pulsos : 1;
    }

    public void setPulsos(Integer pulsos) {
        this.pulsos = pulsos;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }
}
