package br.com.playbox;

import br.com.playbox.dto.HistoricoVinculoDTO;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.HistoricoVinculoMaquina;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.HistoricoVinculoMaquinaRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.HistoricoVinculoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricoVinculoServiceTest {

    private HistoricoVinculoMaquinaRepository historicoRepository;
    private MaquinaRepository maquinaRepository;
    private EstabelecimentoRepository estabelecimentoRepository;
    private TransacaoRepository transacaoRepository;
    private HistoricoVinculoService service;

    @BeforeEach
    void setUp() {
        historicoRepository = mock(HistoricoVinculoMaquinaRepository.class);
        maquinaRepository = mock(MaquinaRepository.class);
        estabelecimentoRepository = mock(EstabelecimentoRepository.class);
        transacaoRepository = mock(TransacaoRepository.class);

        service = new HistoricoVinculoService(
                historicoRepository,
                maquinaRepository,
                estabelecimentoRepository,
                transacaoRepository
        );
    }

    @Test
    void deveAlocarMaquinaETrocarEstabelecimentoFechandoVinculoAnterior() {
        Maquina maquina = new Maquina();
        maquina.setId(10L);
        maquina.setNomeIntegracao("Máquina Teste 10");

        Estabelecimento lojaA = new Estabelecimento();
        lojaA.setId(1L);
        lojaA.setNome("Loja Shopping A");

        Estabelecimento lojaB = new Estabelecimento();
        lojaB.setId(2L);
        lojaB.setNome("Loja Supermercado B");

        when(maquinaRepository.findById(10L)).thenReturn(Optional.of(maquina));
        when(estabelecimentoRepository.findById(2L)).thenReturn(Optional.of(lojaB));

        // Vínculo anterior ativo na Loja A desde 01/01/2026
        LocalDateTime inicioA = LocalDateTime.of(2026, 1, 1, 10, 0);
        HistoricoVinculoMaquina vinculoA = new HistoricoVinculoMaquina(maquina, lojaA, inicioA, null, "Inauguração");
        when(historicoRepository.findFirstByMaquinaIdAndDataFimIsNullOrderByDataInicioDesc(10L))
                .thenReturn(Optional.of(vinculoA));

        when(historicoRepository.save(any(HistoricoVinculoMaquina.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Transferência para Loja B ocorrida em 15/02/2026 às 14:00
        LocalDateTime dataTroca = LocalDateTime.of(2026, 2, 15, 14, 0);
        HistoricoVinculoDTO dto = service.alocarMaquina(10L, 2L, dataTroca, "Transferência para novo ponto");

        assertNotNull(dto);
        assertEquals(2L, dto.estabelecimentoId());
        assertEquals("Loja Supermercado B", dto.estabelecimentoNome());
        assertEquals(dataTroca, dto.dataInicio());
        assertTrue(dto.ativo());

        // Verificar que o vínculo anterior na Loja A foi fechado na data da troca
        assertEquals(dataTroca, vinculoA.getDataFim());
        verify(historicoRepository).save(vinculoA);

        // Verificar que a máquina agora aponta para a Loja B
        assertEquals(lojaB, maquina.getEstabelecimento());
        verify(maquinaRepository).save(maquina);
    }

    @Test
    void deveResolverEstabelecimentoCorretoBaseadoNaDataDaVenda() {
        Maquina maquina = new Maquina();
        maquina.setId(10L);

        Estabelecimento lojaA = new Estabelecimento();
        lojaA.setId(1L);
        lojaA.setNome("Loja Shopping A");

        LocalDateTime txTimestamp = LocalDateTime.of(2026, 1, 20, 15, 30);

        HistoricoVinculoMaquina vinculo = new HistoricoVinculoMaquina(
                maquina,
                lojaA,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0),
                "Operação Jan"
        );

        when(historicoRepository.findVigenteNoMomento(10L, txTimestamp))
                .thenReturn(List.of(vinculo));

        Estabelecimento resolvido = service.findEstabelecimentoVigente(10L, txTimestamp);

        assertNotNull(resolvido);
        assertEquals(1L, resolvido.getId());
        assertEquals("Loja Shopping A", resolvido.getNome());
    }

    @Test
    void deveDesalocarMaquinaFechandoVinculoAtivo() {
        Maquina maquina = new Maquina();
        maquina.setId(10L);

        Estabelecimento lojaA = new Estabelecimento();
        lojaA.setId(1L);
        maquina.setEstabelecimento(lojaA);

        when(maquinaRepository.findById(10L)).thenReturn(Optional.of(maquina));

        HistoricoVinculoMaquina ativo = new HistoricoVinculoMaquina(maquina, lojaA, LocalDateTime.of(2026, 1, 1, 0, 0), null, "Ativo");
        when(historicoRepository.findFirstByMaquinaIdAndDataFimIsNullOrderByDataInicioDesc(10L))
                .thenReturn(Optional.of(ativo));

        LocalDateTime retirada = LocalDateTime.of(2026, 3, 1, 18, 0);
        service.desalocarMaquina(10L, retirada, "Retirada para manutenção");

        assertEquals(retirada, ativo.getDataFim());
        assertEquals("Retirada para manutenção", ativo.getMotivo());
        assertNull(maquina.getEstabelecimento());

        verify(historicoRepository).save(ativo);
        verify(maquinaRepository).save(maquina);
    }
}
