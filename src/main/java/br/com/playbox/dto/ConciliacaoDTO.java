package br.com.playbox.dto;

import java.util.List;

public record ConciliacaoDTO(
        String dataInicio,
        String dataFim,
        Double totalBancoGeral,
        Double totalApiGeral,
        Double diferencaGeral,
        String statusGeral, // "CONCILIADO", "DIVERGENTE"
        int totalMaquinas,
        int totalConciliadas,
        int totalDivergentes,
        List<ItemConciliacaoDTO> itens
) {
    public record ItemConciliacaoDTO(
            Long maquinaId,
            String maquinaNome,
            String maquinaExternalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            String cidade,
            String integracao,
            Double totalBanco,
            Double totalApi,
            Double diferenca,
            Double cashBanco,
            Double cashApi,
            Double pixBanco,
            Double pixApi,
            Double cartaoBanco,
            Double cartaoApi,
            Double estornoBanco,
            Double estornoApi,
            Integer qtdTransacoesBanco,
            String status, // "CONCILIADO", "DIVERGENTE", "SEM_MOVIMENTACAO"
            String mensagem
    ) {}
}
