package br.com.playbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "configuracoes_sistema")
public class ConfiguracaoSistema {

    @Id
    @Column(name = "chave", length = 100)
    private String chave;

    @Column(name = "valor", columnDefinition = "TEXT")
    private String valor;

    @Column(name = "descricao", length = 255)
    private String descricao;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ConfiguracaoSistema() {}

    public ConfiguracaoSistema(String chave, String valor, String descricao) {
        this.chave = chave;
        this.valor = valor;
        this.descricao = descricao;
        this.updatedAt = LocalDateTime.now();
    }

    public String getChave() {
        return chave;
    }

    public void setChave(String chave) {
        this.chave = chave;
    }

    public String getValor() {
        return valor;
    }

    public void setValor(String valor) {
        this.valor = valor;
        this.updatedAt = LocalDateTime.now();
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
