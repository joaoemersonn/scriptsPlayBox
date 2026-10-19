package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "maquinas", uniqueConstraints = {
    @UniqueConstraint(name = "uk_maquina_integracao_external", columnNames = {"integracao", "external_id"})
})
public class Maquina {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "numero_planilha")
    private Integer numeroPlanilha;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String integracao; // VENDPROMAX, PAYXYZ, ZAPIZI

    @Column(name = "nome_integracao")
    private String nomeIntegracao;

    private String descricao; // ex: "Chaves", "Times Cavalinhos", "Peixinho"

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "estabelecimento_id")
    private Estabelecimento estabelecimento;

    private boolean ativo = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Maquina() {}

    public Maquina(Integer numeroPlanilha, String externalId, String integracao, String nomeIntegracao, String descricao, Estabelecimento estabelecimento) {
        this.numeroPlanilha = numeroPlanilha;
        this.externalId = externalId;
        this.integracao = integracao;
        this.nomeIntegracao = nomeIntegracao;
        this.descricao = descricao;
        this.estabelecimento = estabelecimento;
        this.ativo = true;
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

    public Integer getNumeroPlanilha() {
        return numeroPlanilha;
    }

    public void setNumeroPlanilha(Integer numeroPlanilha) {
        this.numeroPlanilha = numeroPlanilha;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getIntegracao() {
        return integracao;
    }

    public void setIntegracao(String integracao) {
        this.integracao = integracao;
    }

    public String getNomeIntegracao() {
        return nomeIntegracao;
    }

    public void setNomeIntegracao(String nomeIntegracao) {
        this.nomeIntegracao = nomeIntegracao;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public Estabelecimento getEstabelecimento() {
        return estabelecimento;
    }

    public void setEstabelecimento(Estabelecimento estabelecimento) {
        this.estabelecimento = estabelecimento;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
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
