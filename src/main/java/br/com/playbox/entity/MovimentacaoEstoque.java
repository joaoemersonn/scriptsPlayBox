package br.com.playbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "movimentacoes_estoque", indexes = {
    @Index(name = "idx_mov_estoque_maquina", columnList = "maquina_id"),
    @Index(name = "idx_mov_estoque_data", columnList = "data_movimentacao"),
    @Index(name = "idx_mov_estoque_ext_ref", columnList = "external_ref")
})
public class MovimentacaoEstoque {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "maquina_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Maquina maquina;

    @Column(nullable = false, length = 30)
    private String tipo; // ABASTECIMENTO, SAIDA_PREMIO, AJUSTE_MANUAL, SINCRONIZACAO

    @Column(nullable = false)
    private Integer quantidade; // Delta da movimentação (positivo para entrada/ajuste pra cima, negativo para saída)

    @Column(name = "estoque_anterior", nullable = false)
    private Integer estoqueAnterior;

    @Column(name = "estoque_novo", nullable = false)
    private Integer estoqueNovo;

    @Column(length = 50)
    private String origem; // VENDPROMAX, PAYXYZ, ZAPIZI, API_MANUAL

    @Column(length = 255)
    private String observacao;

    @Column(length = 100)
    private String responsavel;

    @Column(name = "data_movimentacao", nullable = false)
    private LocalDateTime dataMovimentacao;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    public MovimentacaoEstoque() {
    }

    public MovimentacaoEstoque(Maquina maquina, String tipo, Integer quantidade, Integer estoqueAnterior,
                               Integer estoqueNovo, String origem, String observacao, String responsavel,
                               LocalDateTime dataMovimentacao, String externalRef) {
        this.maquina = maquina;
        this.tipo = tipo;
        this.quantidade = quantidade;
        this.estoqueAnterior = estoqueAnterior;
        this.estoqueNovo = estoqueNovo;
        this.origem = origem;
        this.observacao = observacao;
        this.responsavel = responsavel;
        this.dataMovimentacao = dataMovimentacao != null ? dataMovimentacao : LocalDateTime.now();
        this.externalRef = externalRef;
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

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    public Integer getEstoqueAnterior() {
        return estoqueAnterior;
    }

    public void setEstoqueAnterior(Integer estoqueAnterior) {
        this.estoqueAnterior = estoqueAnterior;
    }

    public Integer getEstoqueNovo() {
        return estoqueNovo;
    }

    public void setEstoqueNovo(Integer estoqueNovo) {
        this.estoqueNovo = estoqueNovo;
    }

    public String getOrigem() {
        return origem;
    }

    public void setOrigem(String origem) {
        this.origem = origem;
    }

    public String getObservacao() {
        return observacao;
    }

    public void setObservacao(String observacao) {
        this.observacao = observacao;
    }

    public String getResponsavel() {
        return responsavel;
    }

    public void setResponsavel(String responsavel) {
        this.responsavel = responsavel;
    }

    public LocalDateTime getDataMovimentacao() {
        return dataMovimentacao;
    }

    public void setDataMovimentacao(LocalDateTime dataMovimentacao) {
        this.dataMovimentacao = dataMovimentacao;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }
}
