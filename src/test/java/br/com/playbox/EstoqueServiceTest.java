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
}
