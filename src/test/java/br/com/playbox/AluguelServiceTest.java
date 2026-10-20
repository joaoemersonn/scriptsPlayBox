package br.com.playbox;

import br.com.playbox.entity.AluguelRepasse;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.repository.AluguelRepasseRepository;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MeioPagamentoRepository;
import br.com.playbox.service.AluguelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AluguelServiceTest {

    @Mock
    private EstabelecimentoRepository estabelecimentoRepository;

    @Mock
    private AluguelRepasseRepository aluguelRepository;

    @Mock
    private MeioPagamentoRepository meioPagamentoRepository;

    private AluguelService service;

    @BeforeEach
    void setUp() {
        service = new AluguelService(estabelecimentoRepository, aluguelRepository, meioPagamentoRepository);
    }

    @Test
    @DisplayName("Não deve gerar cobrança de aluguel para estabelecimento inativo")
    void testNaoGeraAluguelParaInativo() {
        Estabelecimento ativo = new Estabelecimento("Loja Ativa", "Custódia", "João", "Dia 10", 150.0, "2026-01-01");
        ativo.setId(1L);
        ativo.setAtivo(true);

        Estabelecimento inativo = new Estabelecimento("Loja Inativa", "Sertânia", "Paulo", "Dia 10", 150.0, "2026-01-01");
        inativo.setId(2L);
        inativo.setAtivo(false);

        // findByAtivoTrue retorna apenas a loja ativa
        when(estabelecimentoRepository.findByAtivoTrue()).thenReturn(List.of(ativo));
        when(aluguelRepository.findByEstabelecimentoIdAndMesAno(1L, "2026-10")).thenReturn(Optional.empty());
        when(aluguelRepository.save(any(AluguelRepasse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<AluguelRepasse> criados = service.gerarCobrancasMes(YearMonth.of(2026, 10));

        assertEquals(1, criados.size());
        assertEquals("Loja Ativa", criados.get(0).getEstabelecimento().getNome());
        verify(aluguelRepository, times(1)).deleteByEstabelecimentoAtivoFalseAndPagoFalse();
    }

    @Test
    @DisplayName("Deve listar apenas aluguéis pendentes de estabelecimentos ativos")
    void testListarPendentesApenasAtivos() {
        Estabelecimento ativo = new Estabelecimento("Loja Ativa", "Custódia", "João", "Dia 10", 150.0, "2026-01-01");
        ativo.setAtivo(true);
        AluguelRepasse aluguelAtivo = new AluguelRepasse(ativo, "2026-10", LocalDate.of(2026, 10, 10), 150.0);

        when(aluguelRepository.findByPagoFalseOrderByDataVencimentoAsc()).thenReturn(List.of(aluguelAtivo));

        List<AluguelRepasse> pendentes = service.listarPendentes();

        assertEquals(1, pendentes.size());
        assertTrue(pendentes.get(0).getEstabelecimento().isAtivo());
    }

    @Test
    @DisplayName("Deve chamar remoção de cobranças de inativos explicitamente")
    void testRemoverCobrancasPendentesDeInativos() {
        service.removerCobrancasPendentesDeInativos();
        verify(aluguelRepository, times(1)).deleteByEstabelecimentoAtivoFalseAndPagoFalse();
    }
}
