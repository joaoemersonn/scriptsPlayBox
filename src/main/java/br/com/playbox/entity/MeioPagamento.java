package br.com.playbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "meios_pagamento")
public class MeioPagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estabelecimento_id", nullable = false)
    @JsonIgnore
    private Estabelecimento estabelecimento;

    @Column(nullable = false)
    private String tipo; // "PIX", "ESPECIE", "TRANSFERENCIA", etc.

    @Column(name = "chave_ou_detalhe")
    private String chaveOuDetalhe; // a chave pix ou instrução como "Em Espécie💵" ou e-mail

    private String descricao; // ex: "PIX Diogo", "PIX Principal", "Dinheiro em mãos"

    private boolean principal = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public MeioPagamento() {}

    public MeioPagamento(Estabelecimento estabelecimento, String tipo, String chaveOuDetalhe, String descricao, boolean principal) {
        this.estabelecimento = estabelecimento;
        this.tipo = tipo;
        this.chaveOuDetalhe = chaveOuDetalhe;
        this.descricao = descricao;
        this.principal = principal;
        this.createdAt = LocalDateTime.now();
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

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getChaveOuDetalhe() {
        return chaveOuDetalhe;
    }

    public void setChaveOuDetalhe(String chaveOuDetalhe) {
        this.chaveOuDetalhe = chaveOuDetalhe;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public boolean isPrincipal() {
        return principal;
    }

    public void setPrincipal(boolean principal) {
        this.principal = principal;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
