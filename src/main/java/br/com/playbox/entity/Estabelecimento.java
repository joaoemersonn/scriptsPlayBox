package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "estabelecimentos")
public class Estabelecimento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String nome;

    private String cidade;

    private String responsavel;

    @Column(name = "chave_pix")
    private String chavePix;

    @Column(name = "dia_pagamento")
    private String diaPagamento;

    @Column(name = "valor_acordado")
    private Double valorAcordado;

    @Column(name = "data_inicio")
    private String dataInicio;

    private boolean ativo = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Estabelecimento() {}

    public Estabelecimento(String nome, String cidade, String responsavel, String chavePix, String diaPagamento, Double valorAcordado, String dataInicio) {
        this.nome = nome;
        this.cidade = cidade;
        this.responsavel = responsavel;
        this.chavePix = chavePix;
        this.diaPagamento = diaPagamento;
        this.valorAcordado = valorAcordado;
        this.dataInicio = dataInicio;
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

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCidade() {
        return cidade;
    }

    public void setCidade(String cidade) {
        this.cidade = cidade;
    }

    public String getResponsavel() {
        return responsavel;
    }

    public void setResponsavel(String responsavel) {
        this.responsavel = responsavel;
    }

    public String getChavePix() {
        return chavePix;
    }

    public void setChavePix(String chavePix) {
        this.chavePix = chavePix;
    }

    public String getDiaPagamento() {
        return diaPagamento;
    }

    public void setDiaPagamento(String diaPagamento) {
        this.diaPagamento = diaPagamento;
    }

    public Double getValorAcordado() {
        return valorAcordado;
    }

    public void setValorAcordado(Double valorAcordado) {
        this.valorAcordado = valorAcordado;
    }

    public String getDataInicio() {
        return dataInicio;
    }

    public void setDataInicio(String dataInicio) {
        this.dataInicio = dataInicio;
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
