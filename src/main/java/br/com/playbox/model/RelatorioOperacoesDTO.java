package br.com.playbox.model;

import java.util.ArrayList;
import java.util.List;

public class RelatorioOperacoesDTO {

    private String tituloPeriodo;
    private String unidade;
    private String estabelecimentoNome;
    private String cidade;

    // Métricas
    private int quantidadePremios;
    private double valorUnitarioPelucia = 19.90;
    private double custoTotalPelucia;

    // Entradas
    private double entradasEspecie;
    private double entradasMaquininha;
    private double totalEntradas;

    // Saídas
    private double valorEstornado;
    private double taxaMaquininhaPercentual = 4.99;
    private double taxaMaquininhaValor;
    private double mensalidadeTelemetria = 69.90;
    private double visitasManutencao = 350.00;
    private double totalSaidas;

    // Resultado
    private double lucroTotal;
    private double percentualRepasse = 25.0;
    private double valorRepasse;

    // Histórico detalhado
    private List<MovimentacaoItem> movimentacoes = new ArrayList<>();

    public RelatorioOperacoesDTO() {}

    public void recalcularTotais() {
        this.custoTotalPelucia = round(this.quantidadePremios * this.valorUnitarioPelucia);
        this.totalEntradas = round(this.entradasEspecie + this.entradasMaquininha);
        // Taxa da maquininha calculada sobre o total de entradas (como no relatório padrão)
        this.taxaMaquininhaValor = round(this.totalEntradas * (this.taxaMaquininhaPercentual / 100.0));
        this.totalSaidas = round(
                this.valorEstornado +
                this.taxaMaquininhaValor +
                this.custoTotalPelucia +
                this.mensalidadeTelemetria +
                this.visitasManutencao
        );
        this.lucroTotal = round(this.totalEntradas - this.totalSaidas);
        this.valorRepasse = round(this.lucroTotal * (this.percentualRepasse / 100.0));
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    public String getTituloPeriodo() {
        return tituloPeriodo;
    }

    public void setTituloPeriodo(String tituloPeriodo) {
        this.tituloPeriodo = tituloPeriodo;
    }

    public String getUnidade() {
        return unidade;
    }

    public void setUnidade(String unidade) {
        this.unidade = unidade;
    }

    public String getEstabelecimentoNome() {
        return estabelecimentoNome;
    }

    public void setEstabelecimentoNome(String estabelecimentoNome) {
        this.estabelecimentoNome = estabelecimentoNome;
    }

    public String getCidade() {
        return cidade;
    }

    public void setCidade(String cidade) {
        this.cidade = cidade;
    }

    public int getQuantidadePremios() {
        return quantidadePremios;
    }

    public void setQuantidadePremios(int quantidadePremios) {
        this.quantidadePremios = quantidadePremios;
    }

    public double getValorUnitarioPelucia() {
        return valorUnitarioPelucia;
    }

    public void setValorUnitarioPelucia(double valorUnitarioPelucia) {
        this.valorUnitarioPelucia = valorUnitarioPelucia;
    }

    public double getCustoTotalPelucia() {
        return custoTotalPelucia;
    }

    public void setCustoTotalPelucia(double custoTotalPelucia) {
        this.custoTotalPelucia = custoTotalPelucia;
    }

    public double getEntradasEspecie() {
        return entradasEspecie;
    }

    public void setEntradasEspecie(double entradasEspecie) {
        this.entradasEspecie = entradasEspecie;
    }

    public double getEntradasMaquininha() {
        return entradasMaquininha;
    }

    public void setEntradasMaquininha(double entradasMaquininha) {
        this.entradasMaquininha = entradasMaquininha;
    }

    public double getTotalEntradas() {
        return totalEntradas;
    }

    public void setTotalEntradas(double totalEntradas) {
        this.totalEntradas = totalEntradas;
    }

    public double getValorEstornado() {
        return valorEstornado;
    }

    public void setValorEstornado(double valorEstornado) {
        this.valorEstornado = valorEstornado;
    }

    public double getTaxaMaquininhaPercentual() {
        return taxaMaquininhaPercentual;
    }

    public void setTaxaMaquininhaPercentual(double taxaMaquininhaPercentual) {
        this.taxaMaquininhaPercentual = taxaMaquininhaPercentual;
    }

    public double getTaxaMaquininhaValor() {
        return taxaMaquininhaValor;
    }

    public void setTaxaMaquininhaValor(double taxaMaquininhaValor) {
        this.taxaMaquininhaValor = taxaMaquininhaValor;
    }

    public double getMensalidadeTelemetria() {
        return mensalidadeTelemetria;
    }

    public void setMensalidadeTelemetria(double mensalidadeTelemetria) {
        this.mensalidadeTelemetria = mensalidadeTelemetria;
    }

    public double getVisitasManutencao() {
        return visitasManutencao;
    }

    public void setVisitasManutencao(double visitasManutencao) {
        this.visitasManutencao = visitasManutencao;
    }

    public double getTotalSaidas() {
        return totalSaidas;
    }

    public void setTotalSaidas(double totalSaidas) {
        this.totalSaidas = totalSaidas;
    }

    public double getLucroTotal() {
        return lucroTotal;
    }

    public void setLucroTotal(double lucroTotal) {
        this.lucroTotal = lucroTotal;
    }

    public double getPercentualRepasse() {
        return percentualRepasse;
    }

    public void setPercentualRepasse(double percentualRepasse) {
        this.percentualRepasse = percentualRepasse;
    }

    public double getValorRepasse() {
        return valorRepasse;
    }

    public void setValorRepasse(double valorRepasse) {
        this.valorRepasse = valorRepasse;
    }

    public List<MovimentacaoItem> getMovimentacoes() {
        return movimentacoes;
    }

    public void setMovimentacoes(List<MovimentacaoItem> movimentacoes) {
        this.movimentacoes = movimentacoes;
    }

    public static class MovimentacaoItem {
        private String dataHora;
        private String maquina;
        private String valor;
        private String formaPagamento;
        private String identificadorMp;
        private String estornado;
        private String creditadoNaMaquina;
        private String motivoEstorno;
        private boolean isPremio;
        private boolean isEstornado;
        private boolean isNaoCreditado;

        public MovimentacaoItem() {}

        public MovimentacaoItem(
                String dataHora,
                String maquina,
                String valor,
                String formaPagamento,
                String identificadorMp,
                String estornado,
                String creditadoNaMaquina,
                String motivoEstorno,
                boolean isPremio,
                boolean isEstornado,
                boolean isNaoCreditado
        ) {
            this.dataHora = dataHora;
            this.maquina = maquina;
            this.valor = valor;
            this.formaPagamento = formaPagamento;
            this.identificadorMp = identificadorMp;
            this.estornado = estornado;
            this.creditadoNaMaquina = creditadoNaMaquina;
            this.motivoEstorno = motivoEstorno;
            this.isPremio = isPremio;
            this.isEstornado = isEstornado;
            this.isNaoCreditado = isNaoCreditado;
        }

        public String getDataHora() {
            return dataHora;
        }

        public void setDataHora(String dataHora) {
            this.dataHora = dataHora;
        }

        public String getMaquina() {
            return maquina;
        }

        public void setMaquina(String maquina) {
            this.maquina = maquina;
        }

        public String getValor() {
            return valor;
        }

        public void setValor(String valor) {
            this.valor = valor;
        }

        public String getFormaPagamento() {
            return formaPagamento;
        }

        public void setFormaPagamento(String formaPagamento) {
            this.formaPagamento = formaPagamento;
        }

        public String getIdentificadorMp() {
            return identificadorMp;
        }

        public void setIdentificadorMp(String identificadorMp) {
            this.identificadorMp = identificadorMp;
        }

        public String getEstornado() {
            return estornado;
        }

        public void setEstornado(String estornado) {
            this.estornado = estornado;
        }

        public String getCreditadoNaMaquina() {
            return creditadoNaMaquina;
        }

        public void setCreditadoNaMaquina(String creditadoNaMaquina) {
            this.creditadoNaMaquina = creditadoNaMaquina;
        }

        public String getMotivoEstorno() {
            return motivoEstorno;
        }

        public void setMotivoEstorno(String motivoEstorno) {
            this.motivoEstorno = motivoEstorno;
        }

        public boolean isPremio() {
            return isPremio;
        }

        public void setPremio(boolean premio) {
            isPremio = premio;
        }

        public boolean isEstornado() {
            return isEstornado;
        }

        public void setEstornado(boolean estornado) {
            isEstornado = estornado;
        }

        public boolean isNaoCreditado() {
            return isNaoCreditado;
        }

        public void setNaoCreditado(boolean naoCreditado) {
            isNaoCreditado = naoCreditado;
        }
    }
}
