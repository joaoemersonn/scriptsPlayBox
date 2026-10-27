package br.com.playbox.dto;

import java.time.LocalDate;

public record AssociarEstabelecimentoLoteDTO(
        Long maquinaId,
        Long estabelecimentoId,
        LocalDate dataInicio,
        LocalDate dataFim,
        Boolean apenasSemEstabelecimento
) {}
