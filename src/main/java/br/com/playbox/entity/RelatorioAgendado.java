package br.com.playbox.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "relatorios_agendados")
public class RelatorioAgendado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nome;

    @Column(nullable = false, length = 50)
    private String tipo; // FATURAMENTO_24H, FATURAMENTO_12H, FATURAMENTO_MES, FATURAMENTO_HOJE, ALERTA_ESTOQUE

    @Column(nullable = false, length = 30)
    private String frequencia; // DIARIO, INTERVALO, MENSAL, CRON

    @Column(length = 10)
    private String hora; // "00:00", "08:00", etc.

    @Column(name = "intervalo_minutos")
    private Integer intervaloMinutos;

    @Column(name = "dia_do_mes")
    private Integer diaDoMes = 1;

    @Column(name = "cron_expression", length = 50)
    private String cronExpression;

    @Column(name = "tipo_exibicao", length = 40)
    private String tipoExibicao = "DETALHADO_ESTABELECIMENTO"; // GERAL ou DETALHADO_ESTABELECIMENTO

    @Column(name = "whatsapp_chat_id", length = 100)
    private String whatsappChatId;

    @Column(nullable = false)
    private Boolean ativo = true;

    @Column(name = "ultimo_disparo")
    private LocalDateTime ultimoDisparo;

    @Column(name = "ultimo_status", length = 20)
    private String ultimoStatus; // SUCCESS, ERROR

    @Column(name = "ultima_mensagem", columnDefinition = "TEXT")
    private String ultimaMensagem;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public RelatorioAgendado() {}

    public RelatorioAgendado(String nome, String tipo, String frequencia, String hora, Integer intervaloMinutos,
                             Integer diaDoMes, String tipoExibicao, String whatsappChatId, Boolean ativo) {
        this.nome = nome;
        this.tipo = tipo;
        this.frequencia = frequencia;
        this.hora = hora;
        this.intervaloMinutos = intervaloMinutos;
        this.diaDoMes = diaDoMes != null ? diaDoMes : 1;
        this.tipoExibicao = (tipoExibicao != null && !tipoExibicao.isBlank()) ? tipoExibicao : "DETALHADO_ESTABELECIMENTO";
        this.whatsappChatId = whatsappChatId;
        this.ativo = ativo != null ? ativo : true;
        this.createdAt = LocalDateTime.now();
        this.recalcularCron();
    }

    public void recalcularCron() {
        if ("CRON".equalsIgnoreCase(this.frequencia)) {
            // Mantém custom cron se informado
            if (this.cronExpression == null || this.cronExpression.isBlank()) {
                this.cronExpression = "0 0 0 * * *";
            }
            return;
        }

        if ("INTERVALO".equalsIgnoreCase(this.frequencia)) {
            int mins = (this.intervaloMinutos != null && this.intervaloMinutos > 0) ? this.intervaloMinutos : 60;
            if (mins < 60) {
                this.cronExpression = "0 */" + mins + " * * * *";
            } else {
                int hours = Math.max(1, mins / 60);
                this.cronExpression = "0 0 */" + hours + " * * *";
            }
            return;
        }

        if ("MENSAL".equalsIgnoreCase(this.frequencia)) {
            int dia = (this.diaDoMes != null && this.diaDoMes >= 1 && this.diaDoMes <= 31) ? this.diaDoMes : 1;
            String h = (this.hora != null && this.hora.contains(":")) ? this.hora : "08:00";
            String[] parts = h.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int min = Integer.parseInt(parts[1].trim());
            this.cronExpression = String.format("0 %d %d %d * *", min, hour, dia);
            return;
        }

        // Padrão: DIARIO
        String h = (this.hora != null && this.hora.contains(":")) ? this.hora : "00:00";
        String[] parts = h.split(":");
        int hour = Integer.parseInt(parts[0].trim());
        int min = Integer.parseInt(parts[1].trim());
        this.cronExpression = String.format("0 %d %d * * *", min, hour);
    }

    // Getters and Setters
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

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getFrequencia() {
        return frequencia;
    }

    public void setFrequencia(String frequencia) {
        this.frequencia = frequencia;
    }

    public String getHora() {
        return hora;
    }

    public void setHora(String hora) {
        this.hora = hora;
    }

    public Integer getIntervaloMinutos() {
        return intervaloMinutos;
    }

    public void setIntervaloMinutos(Integer intervaloMinutos) {
        this.intervaloMinutos = intervaloMinutos;
    }

    public Integer getDiaDoMes() {
        return diaDoMes;
    }

    public void setDiaDoMes(Integer diaDoMes) {
        this.diaDoMes = diaDoMes;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getTipoExibicao() {
        return tipoExibicao;
    }

    public void setTipoExibicao(String tipoExibicao) {
        this.tipoExibicao = tipoExibicao;
    }

    public String getWhatsappChatId() {
        return whatsappChatId;
    }

    public void setWhatsappChatId(String whatsappChatId) {
        this.whatsappChatId = whatsappChatId;
    }

    public Boolean getAtivo() {
        return ativo;
    }

    public void setAtivo(Boolean ativo) {
        this.ativo = ativo;
    }

    public LocalDateTime getUltimoDisparo() {
        return ultimoDisparo;
    }

    public void setUltimoDisparo(LocalDateTime ultimoDisparo) {
        this.ultimoDisparo = ultimoDisparo;
    }

    public String getUltimoStatus() {
        return ultimoStatus;
    }

    public void setUltimoStatus(String ultimoStatus) {
        this.ultimoStatus = ultimoStatus;
    }

    public String getUltimaMensagem() {
        return ultimaMensagem;
    }

    public void setUltimaMensagem(String ultimaMensagem) {
        this.ultimaMensagem = ultimaMensagem;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
