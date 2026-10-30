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

    @Column(name = "estoque_atual")
    private Integer estoqueAtual = 0;

    @Column(name = "estoque_minimo_alerta")
    private Integer estoqueMinimoAlerta = 30;

    @Column(name = "capacidade_maxima")
    private Integer capacidadeMaxima = 100;

    @Column(name = "ultima_entrada_estoque")
    private LocalDateTime ultimaEntradaEstoque;

    @Column(name = "ultima_saida_estoque")
    private LocalDateTime ultimaSaidaEstoque;

    @Column(name = "ultima_notificacao_estoque_baixo")
    private LocalDateTime ultimaNotificacaoEstoqueBaixo;

    // --- CAMPOS DE TELEMETRIA E HARDWARE IOT NATIVO (PLAYBOX) ---
    private String ip;

    private Integer rssi;

    private Boolean online = false;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "total_digitais")
    private Long totalDigitais = 0L;

    @Column(name = "total_relogio_entrada")
    private Long totalRelogioEntrada = 0L;

    @Column(name = "total_relogio_saida")
    private Long totalRelogioSaida = 0L;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds = 0L;

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

    public Integer getEstoqueAtual() {
        return estoqueAtual != null ? estoqueAtual : 0;
    }

    public void setEstoqueAtual(Integer estoqueAtual) {
        this.estoqueAtual = estoqueAtual;
    }

    public Integer getEstoqueMinimoAlerta() {
        return estoqueMinimoAlerta != null ? estoqueMinimoAlerta : 30;
    }

    public void setEstoqueMinimoAlerta(Integer estoqueMinimoAlerta) {
        this.estoqueMinimoAlerta = estoqueMinimoAlerta;
    }

    public Integer getCapacidadeMaxima() {
        return capacidadeMaxima != null ? capacidadeMaxima : 100;
    }

    public void setCapacidadeMaxima(Integer capacidadeMaxima) {
        this.capacidadeMaxima = capacidadeMaxima;
    }

    public LocalDateTime getUltimaEntradaEstoque() {
        return ultimaEntradaEstoque;
    }

    public void setUltimaEntradaEstoque(LocalDateTime ultimaEntradaEstoque) {
        this.ultimaEntradaEstoque = ultimaEntradaEstoque;
    }

    public LocalDateTime getUltimaSaidaEstoque() {
        return ultimaSaidaEstoque;
    }

    public void setUltimaSaidaEstoque(LocalDateTime ultimaSaidaEstoque) {
        this.ultimaSaidaEstoque = ultimaSaidaEstoque;
    }

    public LocalDateTime getUltimaNotificacaoEstoqueBaixo() {
        return ultimaNotificacaoEstoqueBaixo;
    }

    public void setUltimaNotificacaoEstoqueBaixo(LocalDateTime ultimaNotificacaoEstoqueBaixo) {
        this.ultimaNotificacaoEstoqueBaixo = ultimaNotificacaoEstoqueBaixo;
    }

    public boolean isEstoqueBaixo() {
        return getEstoqueAtual() < getEstoqueMinimoAlerta();
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getRssi() {
        return rssi;
    }

    public void setRssi(Integer rssi) {
        this.rssi = rssi;
    }

    public Boolean getOnline() {
        return online != null ? online : false;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Long getTotalDigitais() {
        return totalDigitais != null ? totalDigitais : 0L;
    }

    public void setTotalDigitais(Long totalDigitais) {
        this.totalDigitais = totalDigitais;
    }

    public Long getTotalRelogioEntrada() {
        return totalRelogioEntrada != null ? totalRelogioEntrada : 0L;
    }

    public void setTotalRelogioEntrada(Long totalRelogioEntrada) {
        this.totalRelogioEntrada = totalRelogioEntrada;
    }

    public Long getTotalRelogioSaida() {
        return totalRelogioSaida != null ? totalRelogioSaida : 0L;
    }

    public void setTotalRelogioSaida(Long totalRelogioSaida) {
        this.totalRelogioSaida = totalRelogioSaida;
    }

    public Long getUptimeSeconds() {
        return uptimeSeconds != null ? uptimeSeconds : 0L;
    }

    public void setUptimeSeconds(Long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }
}
