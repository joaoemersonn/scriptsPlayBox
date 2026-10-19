package br.com.playbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "dia_pagamento")
    private String diaPagamento; // ex: "Dia 01", "Dia 21" ou "01", "21"

    @Column(name = "valor_acordado")
    private Double valorAcordado;

    @Column(name = "data_inicio")
    private String dataInicio;

    private boolean ativo = true;

    @OneToMany(mappedBy = "estabelecimento", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<MeioPagamento> meiosPagamento = new ArrayList<>();

    @OneToMany(mappedBy = "estabelecimento", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Maquina> maquinas = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Estabelecimento() {}

    public Estabelecimento(String nome, String cidade, String responsavel, String diaPagamento, Double valorAcordado, String dataInicio) {
        this.nome = nome;
        this.cidade = cidade;
        this.responsavel = responsavel;
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

    public void addMeioPagamento(MeioPagamento meio) {
        meiosPagamento.add(meio);
        meio.setEstabelecimento(this);
    }

    public void removeMeioPagamento(MeioPagamento meio) {
        meiosPagamento.remove(meio);
        meio.setEstabelecimento(null);
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

    public List<MeioPagamento> getMeiosPagamento() {
        return meiosPagamento;
    }

    public void setMeiosPagamento(List<MeioPagamento> meiosPagamento) {
        this.meiosPagamento = meiosPagamento;
    }

    public List<Maquina> getMaquinas() {
        return maquinas;
    }

    public void setMaquinas(List<Maquina> maquinas) {
        this.maquinas = maquinas;
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
