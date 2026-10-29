package br.com.playbox;

import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.MovimentacaoEstoque;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.MovimentacaoEstoqueRepository;
import br.com.playbox.service.EstoqueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EstoqueServiceTest {

    private MaquinaRepository maquinaRepository;
    private MovimentacaoEstoqueRepository movimentacaoRepository;
    private VendProMaxClient vendClient;
    private AppProperties properties;
    private EstoqueService estoqueService;

    @BeforeEach
    void setUp() {
        maquinaRepository = mock(MaquinaRepository.class);
        movimentacaoRepository = mock(MovimentacaoEstoqueRepository.class);
        vendClient = mock(VendProMaxClient.class);
        properties = new AppProperties();
        estoqueService = new EstoqueService(maquinaRepository, movimentacaoRepository, vendClient, properties);
    }

    @Test
    @DisplayName("Deve adicionar pelúcias corretamente ao estoque")
    void testAdicionarPelucias() {
        Maquina m = new Maquina();
        m.setId(1L);
        m.setEstoqueAtual(20);
        m.setEstoqueMinimoAlerta(15);

        when(maquinaRepository.findById(1L)).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(movimentacaoRepository.save(any(MovimentacaoEstoque.class))).thenAnswer(i -> i.getArgument(0));

        MovimentacaoEstoque mov = estoqueService.adicionarPelucias(1L, 30, "Abastecimento teste", "Joao");

        assertEquals(50, m.getEstoqueAtual());
        assertEquals(20, mov.getEstoqueAnterior());
        assertEquals(50, mov.getEstoqueNovo());
        assertEquals(30, mov.getQuantidade());
        assertEquals("ABASTECIMENTO", mov.getTipo());
        assertFalse(m.isEstoqueBaixo());
    }

    @Test
    @DisplayName("Deve definir estoque diretamente com balanço físico")
    void testDefinirEstoque() {
        Maquina m = new Maquina();
        m.setId(1L);
        m.setEstoqueAtual(40);
        m.setEstoqueMinimoAlerta(15);

        when(maquinaRepository.findById(1L)).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(movimentacaoRepository.save(any(MovimentacaoEstoque.class))).thenAnswer(i -> i.getArgument(0));

        MovimentacaoEstoque mov = estoqueService.definirEstoque(1L, 10, "Contagem física", "Auditor");

        assertEquals(10, m.getEstoqueAtual());
        assertEquals(40, mov.getEstoqueAnterior());
        assertEquals(10, mov.getEstoqueNovo());
        assertEquals(-30, mov.getQuantidade()); // Delta negativo
        assertEquals("AJUSTE_MANUAL", mov.getTipo());
        assertTrue(m.isEstoqueBaixo()); // 10 <= 15
    }

    @Test
    @DisplayName("Deve deduzir prêmios automaticamente com idempotência")
    void testProcessarSaidaAutomaticaIdempotente() {
        Maquina m = new Maquina();
        m.setId(2L);
        m.setEstoqueAtual(25);

        when(movimentacaoRepository.findByExternalRef("SAIDA_VEND_123_2026-10-19")).thenReturn(Optional.empty());
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(movimentacaoRepository.save(any(MovimentacaoEstoque.class))).thenAnswer(i -> i.getArgument(0));

        estoqueService.processarSaidaAutomatica(m, LocalDate.of(2026, 10, 19), 5, "VENDPROMAX", "VEND_123_2026-10-19");

        assertEquals(20, m.getEstoqueAtual());

        // Agora simulando que o mesmo dia foi reimportado com a mesma saída
        MovimentacaoEstoque movExistente = new MovimentacaoEstoque(m, "SAIDA_PREMIO", -5, 25, 20, "VENDPROMAX", null, "SISTEMA", null, "SAIDA_VEND_123_2026-10-19");
        when(movimentacaoRepository.findByExternalRef("SAIDA_VEND_123_2026-10-19")).thenReturn(Optional.of(movExistente));

        estoqueService.processarSaidaAutomatica(m, LocalDate.of(2026, 10, 19), 5, "VENDPROMAX", "VEND_123_2026-10-19");

        // Estoque não deve ter decrementado novamente! Permanece 20.
        assertEquals(20, m.getEstoqueAtual());
    }

    @Test
    @DisplayName("Deve recalcular estoque retroativo deduzindo saídas de prêmios posteriores")
    void testDefinirEstoqueRetroativoComDeducaoDeSaidas() {
        Maquina m = new Maquina();
        m.setId(11L);
        m.setEstoqueAtual(0);
        m.setIntegracao("LOCAL");

        when(maquinaRepository.findById(11L)).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(movimentacaoRepository.save(any(MovimentacaoEstoque.class))).thenAnswer(i -> i.getArgument(0));

        java.time.LocalDateTime dataContagem = java.time.LocalDateTime.now().minusDays(1).withHour(16).withMinute(30);

        // Mock 13 saídas ocorridas após a data da contagem
        when(movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(11L, "SAIDA_PREMIO", dataContagem))
                .thenReturn(13);
        when(movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(11L, "ABASTECIMENTO", dataContagem))
                .thenReturn(0);

        EstoqueService.ResultadoAjusteEstoque res = estoqueService.definirEstoqueComResultado(
                11L, 70, "Contagem física em Sumé", "João", dataContagem
        );

        assertTrue(res.retroativo());
        assertEquals(70, res.contagemInformada());
        assertEquals(13, res.saidasPosteriores());
        assertEquals(57, res.estoqueAtualResultante());
        assertEquals(57, m.getEstoqueAtual());
        assertEquals("CONTAGEM_FISICA", res.movimentacao().getTipo());
        assertEquals(dataContagem, res.movimentacao().getDataMovimentacao());
    }

    @Test
    @DisplayName("Deve simular prévia de recálculo retroativo sem alterar banco")
    void testCalcularPreviaRetroativa() {
        Maquina m = new Maquina();
        m.setId(11L);
        m.setEstoqueAtual(10);
        m.setIntegracao("LOCAL");

        when(maquinaRepository.findById(11L)).thenReturn(Optional.of(m));

        java.time.LocalDateTime dataContagem = java.time.LocalDateTime.now().minusDays(1).withHour(16).withMinute(30);
        when(movimentacaoRepository.sumQuantidadeByMaquinaIdAndTipoAndDataAfter(11L, "SAIDA_PREMIO", dataContagem))
                .thenReturn(13);

        EstoqueService.ResultadoAjusteEstoque previa = estoqueService.calcularPreviaRetroativa(11L, 70, dataContagem);

        assertTrue(previa.retroativo());
        assertEquals(70, previa.contagemInformada());
        assertEquals(13, previa.saidasPosteriores());
        assertEquals(57, previa.estoqueAtualResultante());
        // Máquina em memória não deve ter seu estoque alterado pela prévia
        assertEquals(10, m.getEstoqueAtual());
    }
}
