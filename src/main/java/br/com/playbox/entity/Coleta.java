package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "coletas", indexes = {
    @Index(name = "idx_coletas_maquina", columnList = "maquina_id"),
    @Index(name = "idx_coletas_estabelecimento", columnList = "estabelecimento_id"),
    @Index(name = "idx_coletas_data_hora", columnList = "data_hora_coleta"),
    @Index(name = "idx_coletas_status", columnList = "status")
})
public class Coleta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "maquina_id", nullable = false)
    private Maquina maquina;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "estabelecimento_id")
    private Estabelecimento estabelecimento;

    @Column(name = "data_hora_coleta", nullable = false)
    private LocalDateTime dataHoraColeta;

    @Column(name = "data_hora_ultima_coleta")
    private LocalDateTime dataHoraUltimaColeta;

    @Column(name = "valor_esperado", nullable = false)
    private Double valorEsperado = 0.0;

    @Column(name = "valor_real")
    private Double valorReal;

    @Column(name = "diferenca")
    private Double diferenca;

    @Column(name = "status", nullable = false)
    private String status; // PENDENTE_CONFERENCIA, CONFERIDO, CONFERIDO_DIVERGENTE

    @Column(name = "data_hora_conferencia")
    private LocalDateTime dataHoraConferencia;

    @Column(name = "observacoes", length = 1000)
    private String observacoes;

    @Column(name = "responsavel")
    private String responsavel;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Coleta() {}

    public Coleta(
            Maquina maquina,
            Estabelecimento estabelecimento,
            LocalDateTime dataHoraColeta,
            LocalDateTime dataHoraUltimaColeta,
            Double valorEsperado,
            Double valorReal,
            String observacoes,
            String responsavel
    ) {
        this.maquina = maquina;
        this.estabelecimento = estabelecimento;
        this.dataHoraColeta = dataHoraColeta != null ? dataHoraColeta : LocalDateTime.now();
        this.dataHoraUltimaColeta = dataHoraUltimaColeta;
        this.valorEsperado = valorEsperado != null ? valorEsperado : 0.0;
        this.valorReal = valorReal;
        this.observacoes = observacoes;
        this.responsavel = responsavel;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (valorReal != null) {
            this.diferenca = valorReal - this.valorEsperado;
            this.dataHoraConferencia = LocalDateTime.now();
            this.status = Math.abs(this.diferenca) < 0.01 ? "CONFERIDO" : "CONFERIDO_DIVERGENTE";
        } else {
            this.status = "PENDENTE_CONFERENCIA";
        }
    }

    public void registrarConferencia(Double valorReal, LocalDateTime dataHoraConferencia, String observacoesAdicionais) {
        this.valorReal = valorReal != null ? valorReal : 0.0;
        this.diferenca = this.valorReal - (this.valorEsperado != null ? this.valorEsperado : 0.0);
        this.dataHoraConferencia = dataHoraConferencia != null ? dataHoraConferencia : LocalDateTime.now();
        this.status = Math.abs(this.diferenca) < 0.01 ? "CONFERIDO" : "CONFERIDO_DIVERGENTE";
        if (observacoesAdicionais != null && !observacoesAdicionais.isBlank()) {
            if (this.observacoes == null || this.observacoes.isBlank()) {
                this.observacoes = observacoesAdicionais.trim();
            } else {
                this.observacoes = this.observacoes + " | Conf: " + observacoesAdicionais.trim();
            }
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void cancelar(String motivo) {
        this.status = "CANCELADA";
        if (motivo != null && !motivo.isBlank()) {
            if (this.observacoes == null || this.observacoes.isBlank()) {
                this.observacoes = "[CANCELADA]: " + motivo.trim();
            } else {
                this.observacoes = this.observacoes + " | [CANCELADA]: " + motivo.trim();
            }
        }
        this.updatedAt = LocalDateTime.now();
    }

    @Transient
    public boolean isCancelada() {
        return "CANCELADA".equalsIgnoreCase(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getDataHoraColeta() {
        return dataHoraColeta;
    }

    public void setDataHoraColeta(LocalDateTime dataHoraColeta) {
        this.dataHoraColeta = dataHoraColeta;
    }

    public LocalDateTime getDataHoraUltimaColeta() {
        return dataHoraUltimaColeta;
    }

    public void setDataHoraUltimaColeta(LocalDateTime dataHoraUltimaColeta) {
        this.dataHoraUltimaColeta = dataHoraUltimaColeta;
    }

    public Double getValorEsperado() {
        return valorEsperado;
    }

    public void setValorEsperado(Double valorEsperado) {
        this.valorEsperado = valorEsperado;
    }

    public Double getValorReal() {
        return valorReal;
    }

    public void setValorReal(Double valorReal) {
        this.valorReal = valorReal;
    }

    public Double getDiferenca() {
        return diferenca;
    }

    public void setDiferenca(Double diferenca) {
        this.diferenca = diferenca;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getDataHoraConferencia() {
        return dataHoraConferencia;
    }

    public void setDataHoraConferencia(LocalDateTime dataHoraConferencia) {
        this.dataHoraConferencia = dataHoraConferencia;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }

    public String getResponsavel() {
        return responsavel;
    }

    public void setResponsavel(String responsavel) {
        this.responsavel = responsavel;
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
}
