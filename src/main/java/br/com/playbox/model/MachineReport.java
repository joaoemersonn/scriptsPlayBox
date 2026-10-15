package br.com.playbox.model;

import java.util.Objects;

public class MachineReport {

    private Object idMaquina;
    private String nomeMaquina;
    private double valorEstornos = 0.0;
    private double valorEspecie = 0.0;
    private double valorPix = 0.0;
    private double valorCredito = 0.0;
    private double valorDebito = 0.0;
    private double quantidadePremios = 0.0;
    private double valorTotal = 0.0;
    private double receitaPorPremioLiberado = 0.0;

    public MachineReport() {
    }

    public MachineReport(Object idMaquina, String nomeMaquina) {
        this.idMaquina = idMaquina;
        this.nomeMaquina = nomeMaquina != null ? nomeMaquina.trim() : "N/A";
    }

    public MachineReport(
            Object idMaquina,
            String nomeMaquina,
            double valorEstornos,
            double valorEspecie,
            double valorPix,
            double valorCredito,
            double valorDebito,
            double quantidadePremios
    ) {
        this.idMaquina = idMaquina;
        this.nomeMaquina = nomeMaquina != null ? nomeMaquina.trim() : "N/A";
        this.valorEstornos = valorEstornos;
        this.valorEspecie = valorEspecie;
        this.valorPix = valorPix;
        this.valorCredito = valorCredito;
        this.valorDebito = valorDebito;
        this.quantidadePremios = quantidadePremios;
        updateTotals();
    }

    public void updateTotals() {
        this.valorTotal = this.valorEspecie + this.valorPix + this.valorCredito + this.valorDebito;
        this.receitaPorPremioLiberado = (this.quantidadePremios > 0.0) ? (this.valorTotal / this.quantidadePremios) : 0.0;
    }

    public Object getIdMaquina() {
        return idMaquina;
    }

    public void setIdMaquina(Object idMaquina) {
        this.idMaquina = idMaquina;
    }

    public String getNomeMaquina() {
        return nomeMaquina != null ? nomeMaquina : "N/A";
    }

    public void setNomeMaquina(String nomeMaquina) {
        this.nomeMaquina = nomeMaquina != null ? nomeMaquina.trim() : "N/A";
    }

    public double getValorEstornos() {
        return valorEstornos;
    }

    public void setValorEstornos(double valorEstornos) {
        this.valorEstornos = valorEstornos;
    }

    public double getValorEspecie() {
        return valorEspecie;
    }

    public void setValorEspecie(double valorEspecie) {
        this.valorEspecie = valorEspecie;
    }

    public double getValorPix() {
        return valorPix;
    }

    public void setValorPix(double valorPix) {
        this.valorPix = valorPix;
    }

    public double getValorCredito() {
        return valorCredito;
    }

    public void setValorCredito(double valorCredito) {
        this.valorCredito = valorCredito;
    }

    public double getValorDebito() {
        return valorDebito;
    }

    public void setValorDebito(double valorDebito) {
        this.valorDebito = valorDebito;
    }

    public double getQuantidadePremios() {
        return quantidadePremios;
    }

    public void setQuantidadePremios(double quantidadePremios) {
        this.quantidadePremios = quantidadePremios;
    }

    public double getValorTotal() {
        return valorTotal;
    }

    public void setValorTotal(double valorTotal) {
        this.valorTotal = valorTotal;
    }

    public double getReceitaPorPremioLiberado() {
        return receitaPorPremioLiberado;
    }

    public void setReceitaPorPremioLiberado(double receitaPorPremioLiberado) {
        this.receitaPorPremioLiberado = receitaPorPremioLiberado;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MachineReport that = (MachineReport) o;
        return Objects.equals(idMaquina, that.idMaquina) && Objects.equals(nomeMaquina, that.nomeMaquina);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idMaquina, nomeMaquina);
    }

    @Override
    public String toString() {
        return "MachineReport{" +
                "idMaquina=" + idMaquina +
                ", nomeMaquina='" + nomeMaquina + '\'' +
                ", valorTotal=" + valorTotal +
                ", quantidadePremios=" + quantidadePremios +
                ", receitaPorPremioLiberado=" + receitaPorPremioLiberado +
                '}';
    }
}

