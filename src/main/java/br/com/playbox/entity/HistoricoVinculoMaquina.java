package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "historico_vinculos_maquina", indexes = {
    @Index(name = "idx_hist_maq", columnList = "maquina_id"),
    @Index(name = "idx_hist_est", columnList = "estabelecimento_id"),
    @Index(name = "idx_hist_datas", columnList = "data_inicio, data_fim")
})
public class HistoricoVinculoMaquina {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "maquina_id", nullable = false)
    private Maquina maquina;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "estabelecimento_id", nullable = false)
    private Estabelecimento estabelecimento;

    @Column(name = "data_inicio", nullable = false)
    private LocalDateTime dataInicio;

    @Column(name = "data_fim")
    private LocalDateTime dataFim;

    @Column(name = "motivo")
    private String motivo;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public HistoricoVinculoMaquina() {}

    public HistoricoVinculoMaquina(Maquina maquina, Estabelecimento estabelecimento, LocalDateTime dataInicio, LocalDateTime dataFim, String motivo) {
        this.maquina = maquina;
        this.estabelecimento = estabelecimento;
        this.dataInicio = dataInicio != null ? dataInicio : LocalDateTime.now();
        this.dataFim = dataFim;
        this.motivo = motivo;
        this.createdAt = LocalDateTime.now();
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

    public LocalDateTime getDataInicio() {
        return dataInicio;
    }

    public void setDataInicio(LocalDateTime dataInicio) {
        this.dataInicio = dataInicio;
    }

    public LocalDateTime getDataFim() {
        return dataFim;
    }

    public void setDataFim(LocalDateTime dataFim) {
        this.dataFim = dataFim;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isAtivo() {
        return this.dataFim == null;
    }
}
