package br.com.playbox;

import br.com.playbox.entity.AluguelRepasse;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.model.RelatorioOperacoesDTO;
import br.com.playbox.repository.AluguelRepasseRepository;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MeioPagamentoRepository;
import br.com.playbox.service.AluguelService;
import br.com.playbox.service.RelatorioOperacoesService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AluguelServiceTest {

    @Mock
    private EstabelecimentoRepository estabelecimentoRepository;

    @Mock
    private AluguelRepasseRepository aluguelRepository;

    @Mock
    private MeioPagamentoRepository meioPagamentoRepository;

    @Mock
    private RelatorioOperacoesService relatorioOperacoesService;

    private AluguelService service;

    @BeforeEach
    void setUp() {
        service = new AluguelService(
                estabelecimentoRepository,
                aluguelRepository,
                meioPagamentoRepository,
                relatorioOperacoesService
        );
    }

    @Test
    @DisplayName("Não deve gerar cobrança de aluguel para estabelecimento inativo")
    void testNaoGeraAluguelParaInativo() {
        Estabelecimento ativo = new Estabelecimento("Loja Ativa", "Custódia", "João", "Dia 10", 150.0, "2026-01-01");
        ativo.setId(1L);
        ativo.setAtivo(true);
        ativo.setTipoAluguel("FIXO");

        when(estabelecimentoRepository.findByAtivoTrue()).thenReturn(List.of(ativo));
        when(aluguelRepository.findByEstabelecimentoIdAndMesAno(1L, "2026-10")).thenReturn(Optional.empty());
        when(aluguelRepository.save(any(AluguelRepasse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<AluguelRepasse> criados = service.gerarCobrancasMes(YearMonth.of(2026, 10));

        assertEquals(1, criados.size());
        assertEquals("Loja Ativa", criados.get(0).getEstabelecimento().getNome());
        assertEquals(150.0, criados.get(0).getValor());
        assertEquals("FIXO", criados.get(0).getTipoAluguel());
        verify(aluguelRepository, times(1)).deleteByEstabelecimentoAtivoFalseAndPagoFalse();
    }

    @Test
    @DisplayName("Deve gerar aluguel dinâmico (25% sobre o lucro) para loja configurada como DINAMICO")
    void testGerarAluguelDinamico() {
        Estabelecimento praca = new Estabelecimento("Praça do Sabor", "Custódia", "Gabriel", "Dia 06", 150.0, "2025-01-01");
        praca.setId(3L);
        praca.setAtivo(true);
        praca.setTipoAluguel("DINAMICO");
        praca.setPercentualRepasse(25.0);

        RelatorioOperacoesDTO dto = new RelatorioOperacoesDTO();
        dto.setLucroTotal(992.98);
        dto.setPercentualRepasse(25.0);
        dto.setValorRepasse(248.24);

        when(estabelecimentoRepository.findByAtivoTrue()).thenReturn(List.of(praca));
        when(aluguelRepository.findByEstabelecimentoIdAndMesAno(3L, "2026-08")).thenReturn(Optional.empty());
        // Cobrança a ser paga em 2026-08 baseia-se nas operações do mês fechado anterior: 2026-07
        when(relatorioOperacoesService.compilarRelatorio(
                eq(3L), isNull(), isNull(),
                eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)),
                eq(19.90), eq(4.99), eq(69.90), eq(350.00), eq(25.0), isNull()
        )).thenReturn(dto);
        when(aluguelRepository.save(any(AluguelRepasse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<AluguelRepasse> criados = service.gerarCobrancasMes(YearMonth.of(2026, 8));

        assertEquals(1, criados.size());
        AluguelRepasse repasse = criados.get(0);
        assertEquals("DINAMICO", repasse.getTipoAluguel());
        assertEquals("2026-07", repasse.getMesCompetencia());
        assertEquals(248.24, repasse.getValor());
        assertEquals(992.98, repasse.getLucroCalculado());
        assertEquals(25.0, repasse.getPercentualRepasse());
        assertFalse(repasse.getValorManual());
    }

    @Test
    @DisplayName("Deve permitir definir valor manual do aluguel")
    void testDefinirValorManual() {
        Estabelecimento est = new Estabelecimento("Loja", "Cidade", "Resp", "Dia 10", 150.0, "2026-01-01");
        AluguelRepasse aluguel = new AluguelRepasse(est, "2026-10", LocalDate.of(2026, 10, 10), 150.0);
        aluguel.setId(5L);

        when(aluguelRepository.findById(5L)).thenReturn(Optional.of(aluguel));
        when(aluguelRepository.save(any(AluguelRepasse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AluguelRepasse atualizado = service.definirValorAluguel(5L, 320.0, "Acordo extraordinário");

        assertEquals(320.0, atualizado.getValor());
        assertTrue(atualizado.getValorManual());
        assertEquals("Acordo extraordinário", atualizado.getComprovanteOuObs());
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

    @Test
    @DisplayName("Deve listar e resumir aluguéis com filtros de status e cálculo de vencidos")
    void testListarEResumoComFiltros() {
        Estabelecimento loja1 = new Estabelecimento("Loja 1", "Custódia", "João", "Dia 05", 150.0, "2026-01-01");
        loja1.setId(1L);
        loja1.setAtivo(true);

        Estabelecimento loja2 = new Estabelecimento("Loja 2", "Monteiro", "Maria", "Dia 28", 200.0, "2026-01-01");
        loja2.setId(2L);
        loja2.setAtivo(true);

        // Aluguel 1: vencido no passado e não pago
        AluguelRepasse a1 = new AluguelRepasse(loja1, "2026-10", LocalDate.of(2026, 10, 5), 150.0);
        a1.setPago(false);

        // Aluguel 2: a vencer no futuro e não pago
        AluguelRepasse a2 = new AluguelRepasse(loja2, "2026-10", LocalDate.of(2026, 10, 28), 200.0);
        a2.setPago(false);

        when(aluguelRepository.findByMesAnoOrderByDataVencimentoAsc("2026-10")).thenReturn(List.of(a1, a2));

        var listaTodos = service.listar("2026-10", "TODOS", null, null);
        assertEquals(2, listaTodos.size());

        var resumo = service.obterResumo("2026-10");
        assertEquals(350.0, resumo.get("totalGeral"));
        assertEquals(0.0, resumo.get("totalPago"));
        assertEquals(2, resumo.get("qtdTotal"));
        assertEquals(2, resumo.get("qtdEmAberto"));
    }
}

