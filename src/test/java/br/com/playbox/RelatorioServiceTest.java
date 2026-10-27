package br.com.playbox;

import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.model.MachineReport;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.RelatorioService;
import br.com.playbox.service.TransactionImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class RelatorioServiceTest {

    private TransacaoRepository transacaoRepository;
    private TransactionImportService transactionImportService;
    private RelatorioService relatorioService;

    @BeforeEach
    void setUp() {
        transacaoRepository = Mockito.mock(TransacaoRepository.class);
        transactionImportService = Mockito.mock(TransactionImportService.class);
        relatorioService = new RelatorioService(transacaoRepository, transactionImportService);
    }

    @Test
    void testRelatorioDesconsideraTransacaoTeste() {
        Maquina m1 = new Maquina();
        m1.setId(1L);
        m1.setNomeIntegracao("Grua Sumé");

        Estabelecimento e1 = new Estabelecimento();
        e1.setId(10L);
        e1.setNome("Shopping Sumé");
        m1.setEstabelecimento(e1);

        // Transação normal: R$ 100 (50 cash, 50 pix)
        Transacao t1 = new Transacao();
        t1.setId(101L);
        t1.setMaquina(m1);
        t1.setEstabelecimento(e1);
        t1.setTimestamp(LocalDateTime.of(2026, 10, 10, 14, 0));
        t1.setValor(100.0);
        t1.setValorCash(50.0);
        t1.setValorPix(50.0);
        t1.setTeste(false);

        // Transação 100% teste: R$ 1479 (1479 cash - bancada antes de abrir a loja)
        Transacao t2 = new Transacao();
        t2.setId(102L);
        t2.setMaquina(m1);
        t2.setEstabelecimento(e1);
        t2.setTimestamp(LocalDateTime.of(2026, 10, 7, 10, 0));
        t2.setValor(1479.0);
        t2.setValorCash(1479.0);
        t2.setTeste(true);
        t2.setValorTeste(0.0); // 100% teste
        t2.setMotivoTeste("Pulsos de teste da equipe na bancada");

        // Transação com teste parcial: R$ 80 (80 cash), sendo R$ 20 teste
        Transacao t3 = new Transacao();
        t3.setId(103L);
        t3.setMaquina(m1);
        t3.setEstabelecimento(e1);
        t3.setTimestamp(LocalDateTime.of(2026, 10, 15, 16, 0));
        t3.setValor(80.0);
        t3.setValorCash(80.0);
        t3.setTeste(true);
        t3.setValorTeste(20.0); // R$ 20 abatido
        t3.setMotivoTeste("Ajuste de força da garra");

        when(transacaoRepository.findFiltered(any(), any(), any(), any(), any(), any())).thenReturn(List.of(t1, t2, t3));

        Map<String, Object> rel = relatorioService.gerarRelatorioJson("mes", null, null, null, null, null, null, false);
        assertNotNull(rel);

        @SuppressWarnings("unchecked")
        Map<String, Object> resumo = (Map<String, Object>) rel.get("resumoGeral");
        assertNotNull(resumo);

        // Total bruto esperado:
        // t1: 100
        // t2: 0 (100% teste desconsiderado)
        // t3: 60 (80 - 20)
        // Total faturamento: 160.0
        assertEquals(160.0, resumo.get("faturamentoBruto"), "Faturamento bruto deve desconsiderar testes");
        assertEquals(160.0, resumo.get("faturamentoLiquido"));
        assertEquals(1499.0, resumo.get("totalTestesDesconsiderados"), "Testes desconsiderados deve ser 1479 + 20 = 1499");

        @SuppressWarnings("unchecked")
        Map<String, Double> formas = (Map<String, Double>) resumo.get("formasPagamento");
        // Cash esperado: t1 (50) + t2 (0) + t3 (80-20=60) = 110.0
        assertEquals(110.0, formas.get("especie"), "Cash deve ser descontado");
        assertEquals(50.0, formas.get("pix"));
    }

    @Test
    void testGetMachineReportsFromDatabaseDesconsideraTestes() {
        Maquina m = new Maquina();
        m.setId(1L);
        m.setNomeIntegracao("Grua Sumé");

        Transacao tNormal = new Transacao();
        tNormal.setMaquina(m);
        tNormal.setValor(200.0);
        tNormal.setValorCash(100.0);
        tNormal.setValorPix(100.0);
        tNormal.setQuantidadePremios(4.0);
        tNormal.setTeste(false);

        Transacao tTeste = new Transacao();
        tTeste.setMaquina(m);
        tTeste.setValor(1479.0);
        tTeste.setValorCash(1479.0);
        tTeste.setQuantidadePremios(10.0);
        tTeste.setTeste(true);
        tTeste.setValorTeste(0.0);

        when(transacaoRepository.findFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(tNormal, tTeste));

        List<MachineReport> reports = relatorioService.getMachineReportsFromDatabase(
                LocalDateTime.now().minusDays(30), LocalDateTime.now(), null, null, null, null
        );

        assertEquals(1, reports.size());
        MachineReport rep = reports.get(0);
        assertEquals(200.0, rep.getValorTotal(), "O relatório da máquina deve ignorar os R$ 1479 de teste");
        assertEquals(100.0, rep.getValorEspecie());
        assertEquals(100.0, rep.getValorPix());
        assertEquals(4.0, rep.getQuantidadePremios());
    }
}
