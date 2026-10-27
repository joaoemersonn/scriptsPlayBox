package br.com.playbox.dto;

import java.time.LocalDateTime;

public record HistoricoVinculoDTO(
        Long id,
        Long maquinaId,
        String maquinaNome,
        Long estabelecimentoId,
        String estabelecimentoNome,
        String estabelecimentoCidade,
        LocalDateTime dataInicio,
        LocalDateTime dataFim,
        String motivo,
        boolean ativo
) {}
