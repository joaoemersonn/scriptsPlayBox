package br.com.playbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "alugueis_repasse", uniqueConstraints = {
    @UniqueConstraint(name = "uk_aluguel_estabelecimento_mes", columnNames = {"estabelecimento_id", "mes_ano"})
})
public class AluguelRepasse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "estabelecimento_id", nullable = false)
    private Estabelecimento estabelecimento;

    @Column(name = "mes_ano", nullable = false)
    private String mesAno; // ex: "2026-10"

    @Column(name = "data_vencimento", nullable = false)
    private LocalDate dataVencimento;

    @Column(nullable = false)
    private Double valor;

    private boolean pago = false;

    @Column(name = "data_pagamento")
    private LocalDate dataPagamento;

    @Column(name = "meio_pagamento_utilizado")
    private String meioPagamentoUtilizado; // ex: "PIX", "ESPECIE"

    @Column(name = "comprovante_ou_obs")
    private String comprovanteOuObs;

    @Column(name = "tipo_aluguel")
    private String tipoAluguel = "FIXO"; // "FIXO" ou "DINAMICO"

    @Column(name = "mes_competencia")
    private String mesCompetencia; // Mês fechado anterior cujas operações serviram de base para o repasse

    @Column(name = "lucro_calculado")
    private Double lucroCalculado;

    @Column(name = "percentual_repasse")
    private Double percentualRepasse;

    @Column(name = "valor_manual")
    private Boolean valorManual = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public AluguelRepasse() {}

    public AluguelRepasse(Estabelecimento estabelecimento, String mesAno, LocalDate dataVencimento, Double valor) {
        this.estabelecimento = estabelecimento;
        this.mesAno = mesAno;
        this.dataVencimento = dataVencimento;
        this.valor = valor;
        this.pago = false;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Estabelecimento getEstabelecimento() {
        return estabelecimento;
    }

    public void setEstabelecimento(Estabelecimento estabelecimento) {
        this.estabelecimento = estabelecimento;
    }

    public String getMesAno() {
        return mesAno;
    }

    public void setMesAno(String mesAno) {
        this.mesAno = mesAno;
    }

    public LocalDate getDataVencimento() {
        return dataVencimento;
    }

    public void setDataVencimento(LocalDate dataVencimento) {
        this.dataVencimento = dataVencimento;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public boolean isPago() {
        return pago;
    }

    public void setPago(boolean pago) {
        this.pago = pago;
    }

    public LocalDate getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(LocalDate dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public String getMeioPagamentoUtilizado() {
        return meioPagamentoUtilizado;
    }

    public void setMeioPagamentoUtilizado(String meioPagamentoUtilizado) {
        this.meioPagamentoUtilizado = meioPagamentoUtilizado;
    }

    public String getComprovanteOuObs() {
        return comprovanteOuObs;
    }

    public void setComprovanteOuObs(String comprovanteOuObs) {
        this.comprovanteOuObs = comprovanteOuObs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getTipoAluguel() {
        return tipoAluguel;
    }

    public void setTipoAluguel(String tipoAluguel) {
        this.tipoAluguel = tipoAluguel;
    }

    public Double getLucroCalculado() {
        return lucroCalculado;
    }

    public void setLucroCalculado(Double lucroCalculado) {
        this.lucroCalculado = lucroCalculado;
    }

    public String getMesCompetencia() {
        return mesCompetencia;
    }

    public void setMesCompetencia(String mesCompetencia) {
        this.mesCompetencia = mesCompetencia;
    }

    public Double getPercentualRepasse() {
        return percentualRepasse;
    }

    public void setPercentualRepasse(Double percentualRepasse) {
        this.percentualRepasse = percentualRepasse;
    }

    public Boolean getValorManual() {
        return valorManual;
    }

    public void setValorManual(Boolean valorManual) {
        this.valorManual = valorManual;
    }

    @Transient
    public boolean isAluguelDinamico() {
        return "DINAMICO".equalsIgnoreCase(tipoAluguel);
    }

    @Transient
    public boolean isVencido() {
        return !pago && dataVencimento != null && dataVencimento.isBefore(LocalDate.now());
    }

    @Transient
    public long getDiasAtraso() {
        if (isVencido()) {
            return java.time.temporal.ChronoUnit.DAYS.between(dataVencimento, LocalDate.now());
        }
        return 0;
    }

    @Transient
    public String getStatusFormatado() {
        if (pago) return "PAGO";
        if (isVencido()) return "VENCIDO";
        return "EM_ABERTO";
    }
}

