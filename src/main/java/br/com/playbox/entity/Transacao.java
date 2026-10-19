package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "transacoes", uniqueConstraints = {
    @UniqueConstraint(name = "uk_transacao_integracao_external", columnNames = {"integracao", "external_tx_id"})
}, indexes = {
    @Index(name = "idx_transacoes_timestamp", columnList = "timestamp"),
    @Index(name = "idx_transacoes_estabelecimento", columnList = "estabelecimento_id"),
    @Index(name = "idx_transacoes_maquina", columnList = "maquina_id")
})
public class Transacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_tx_id", nullable = false)
    private String externalTxId;

    @Column(nullable = false)
    private String integracao; // VENDPROMAX, PAYXYZ, ZAPIZI

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "maquina_id")
    private Maquina maquina;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "estabelecimento_id")
    private Estabelecimento estabelecimento;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "tipo_pagamento")
    private String tipoPagamento; // CONSOLIDADO, PIX, etc.

    @Column(name = "valor_cash")
    private Double valorCash = 0.0;

    @Column(name = "valor_pix")
    private Double valorPix = 0.0;

    @Column(name = "valor_credito")
    private Double valorCredito = 0.0;

    @Column(name = "valor_debito")
    private Double valorDebito = 0.0;

    @Column(nullable = false)
    private Double valor = 0.0; // Total faturado bruto

    @Column(name = "valor_estorno")
    private Double valorEstorno = 0.0;

    @Column(name = "quantidade_premios")
    private Double quantidadePremios = 0.0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Transacao() {}

    public Transacao(String externalTxId, String integracao, Maquina maquina, Estabelecimento estabelecimento,
                     LocalDateTime timestamp, String tipoPagamento, Double valor, Double valorCash,
                     Double valorPix, Double valorCredito, Double valorDebito, Double valorEstorno, Double quantidadePremios) {
        this.externalTxId = externalTxId;
        this.integracao = integracao;
        this.maquina = maquina;
        this.estabelecimento = estabelecimento;
        this.timestamp = timestamp;
        this.tipoPagamento = tipoPagamento;
        this.valor = valor != null ? valor : 0.0;
        this.valorCash = valorCash != null ? valorCash : 0.0;
        this.valorPix = valorPix != null ? valorPix : 0.0;
        this.valorCredito = valorCredito != null ? valorCredito : 0.0;
        this.valorDebito = valorDebito != null ? valorDebito : 0.0;
        this.valorEstorno = valorEstorno != null ? valorEstorno : 0.0;
        this.quantidadePremios = quantidadePremios != null ? quantidadePremios : 0.0;
        this.createdAt = LocalDateTime.now();
    }

    public Transacao(String externalTxId, String integracao, Maquina maquina, Estabelecimento estabelecimento,
                     LocalDateTime timestamp, String tipoPagamento, Double valor, Double valorEstorno, Double quantidadePremios) {
        this(externalTxId, integracao, maquina, estabelecimento, timestamp, tipoPagamento, valor, 0.0, 0.0, 0.0, 0.0, valorEstorno, quantidadePremios);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExternalTxId() {
        return externalTxId;
    }

    public void setExternalTxId(String externalTxId) {
        this.externalTxId = externalTxId;
    }

    public String getIntegracao() {
        return integracao;
    }

    public void setIntegracao(String integracao) {
        this.integracao = integracao;
    }

    public Maquina getMaquina() {
        return maquina;
    }

    public void setMaquina(Maquina maquina) {
        this.maquina = maquina;
    }

    public Estabelecimento getEstabelecimento() {
        return estabelecimento;
    }

    public void setEstabelecimento(Estabelecimento estabelecimento) {
        this.estabelecimento = estabelecimento;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getTipoPagamento() {
        return tipoPagamento;
    }

    public void setTipoPagamento(String tipoPagamento) {
        this.tipoPagamento = tipoPagamento;
    }

    public Double getValorCash() {
        return valorCash != null ? valorCash : 0.0;
    }

    public void setValorCash(Double valorCash) {
        this.valorCash = valorCash;
    }

    public Double getValorPix() {
        return valorPix != null ? valorPix : 0.0;
    }

    public void setValorPix(Double valorPix) {
        this.valorPix = valorPix;
    }

    public Double getValorCredito() {
        return valorCredito != null ? valorCredito : 0.0;
    }

    public void setValorCredito(Double valorCredito) {
        this.valorCredito = valorCredito;
    }

    public Double getValorDebito() {
        return valorDebito != null ? valorDebito : 0.0;
    }

    public void setValorDebito(Double valorDebito) {
        this.valorDebito = valorDebito;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public Double getValorEstorno() {
        return valorEstorno;
    }

    public void setValorEstorno(Double valorEstorno) {
        this.valorEstorno = valorEstorno;
    }

    public Double getQuantidadePremios() {
        return quantidadePremios;
    }

    public void setQuantidadePremios(Double quantidadePremios) {
        this.quantidadePremios = quantidadePremios;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
