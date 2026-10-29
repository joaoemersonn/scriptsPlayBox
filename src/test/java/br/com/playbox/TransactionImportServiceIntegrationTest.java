package br.com.playbox;

import br.com.playbox.client.PayXYZClient;
import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.dto.ConciliacaoDTO;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.ConciliacaoService;
import br.com.playbox.service.EstoqueService;
import br.com.playbox.service.HistoricoVinculoService;
import br.com.playbox.service.TransactionImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest
class TransactionImportServiceIntegrationTest {

    @Autowired
    private MaquinaRepository maquinaRepository;

    @Autowired
    private EstabelecimentoRepository estabelecimentoRepository;

    @Autowired
    private TransacaoRepository transacaoRepository;

    @Autowired
    private EstoqueService estoqueService;

    @Autowired
    private HistoricoVinculoService historicoVinculoService;

    private VendProMaxClient vendClient;
    private PayXYZClient payxyzClient;
    private ZapiziClient zapiziClient;
    private AppProperties properties;
    private TransactionImportService importService;
    private ConciliacaoService conciliacaoService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        vendClient = Mockito.mock(VendProMaxClient.class);
        payxyzClient = Mockito.mock(PayXYZClient.class);
        zapiziClient = Mockito.mock(ZapiziClient.class);

        properties = new AppProperties();
        properties.getVendpromax().setEmail("test@vendpromax.com");
        properties.getVendpromax().setPassword("pass");
        properties.getZapizi().setEmail("test@zapizi.com");
        properties.getZapizi().setPassword("pass");
        properties.getPayxyz().setEmail("test@payxyz.com");
        properties.getPayxyz().setPassword("pass");

        importService = new TransactionImportService(
                vendClient,
                payxyzClient,
                zapiziClient,
                properties,
                maquinaRepository,
                transacaoRepository,
                estoqueService,
                historicoVinculoService
        );

        conciliacaoService = new ConciliacaoService(
                maquinaRepository,
                transacaoRepository,
                vendClient,
                payxyzClient,
                zapiziClient,
                properties
        );
    }

    @Test
    void testImportacaoDetalhadaESubstituicaoConsolidado() throws Exception {
        // 1. Criar ou obter estabelecimento e máquina de teste com nome único
        String testSuffix = String.valueOf(System.currentTimeMillis());
        Estabelecimento est = new Estabelecimento();
        est.setNome("Loja Shopping Teste " + testSuffix);
        est.setCidade("Recife");
        est = estabelecimentoRepository.save(est);

        String extCode = "POSX-TEST-" + testSuffix;
        Maquina maqZap = new Maquina();
        maqZap.setNomeIntegracao("Grua Zapizi Teste " + testSuffix);
        maqZap.setExternalId(extCode);
        maqZap.setIntegracao("ZAPIZI");
        maqZap.setEstabelecimento(est);
        maqZap = maquinaRepository.save(maqZap);

        LocalDate dataTeste = LocalDate.of(2026, 10, 27);

        // 2. Inserir uma transação consolidada legada antiga
        String legacyId = "ZAPIZI_" + extCode + "_2026-10-27";
        Transacao txLegada = new Transacao(
                legacyId,
                "ZAPIZI",
                maqZap,
                est,
                dataTeste.atStartOfDay(),
                "CONSOLIDADO",
                50.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
        );
        transacaoRepository.save(txLegada);

        assertTrue(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", legacyId).isPresent());

        // 3. Mock Zapizi core/sales com vendas detalhadas individuais (3 vendas de R$ 10 = R$ 30)
        String zapSalesJson = """
            {
              "data": [
                {
                  "id": "1001",
                  "machineUniqueCode": "%s",
                  "paymentProvider": "PIX",
                  "total": "10.00",
                  "status": "PAID",
                  "created": "2026-10-27T14:30:00.000Z"
                },
                {
                  "id": "1002",
                  "machineUniqueCode": "%s",
                  "paymentProvider": "PIX",
                  "total": "10.00",
                  "status": "PAID",
                  "created": "2026-10-27T15:15:00.000Z"
                },
                {
                  "id": "1003",
                  "machineUniqueCode": "%s",
                  "paymentProvider": "PIX",
                  "total": "10.00",
                  "status": "PAID",
                  "created": "2026-10-27T16:00:00.000Z"
                }
              ]
            }
        """.formatted(extCode, extCode, extCode);

        // Mock telemetria com cash (R$ 20 em cédulas)
        String zapTelemJson = """
            {
              "telemetryList": [
                {
                  "machineUniqueCode": "%s",
                  "cash": 20.0,
                  "products": 1.0
                }
              ]
            }
        """.formatted(extCode);

        String zapLoginJson = "{\"token\": \"tokZap\", \"refreshToken\": \"refZap\"}";

        when(zapiziClient.login(any(), any(), any())).thenReturn(objectMapper.readTree(zapLoginJson));
        when(zapiziClient.getSales(any(), any(), any(), eq(0), anyInt(), any(), any()))
                .thenReturn(objectMapper.readTree(zapSalesJson));
        when(zapiziClient.getTelemetry(any(), any(), any(), any(), any()))
                .thenReturn(objectMapper.readTree(zapTelemJson));

        // 4. Executar importação detalhada
        int imported = importService.importDailyTransactions(dataTeste);
        assertTrue(imported >= 4, "Deve importar 3 vendas individuais + 1 registro de cash");

        // 5. Verificar que o consolidado antigo foi removido automaticamente
        assertFalse(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", legacyId).isPresent(),
                "A transação consolidada legada antiga deve ter sido removida!");

        // 6. Verificar que as transações detalhadas individuais existem
        assertTrue(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", "ZAPIZI_TX_1001").isPresent());
        assertTrue(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", "ZAPIZI_TX_1002").isPresent());
        assertTrue(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", "ZAPIZI_TX_1003").isPresent());
        assertTrue(transacaoRepository.findByIntegracaoAndExternalTxId("ZAPIZI", "ZAPIZI_CASH_" + extCode + "_2026-10-27").isPresent());

        // 7. Executar conciliação e validar que bate 100%
        ConciliacaoDTO conciliacao = conciliacaoService.conciliar(dataTeste, dataTeste, est.getId(), maqZap.getId(), "ZAPIZI");
        assertNotNull(conciliacao);
        assertEquals("CONCILIADO", conciliacao.statusGeral());
        assertEquals(50.0, conciliacao.totalBancoGeral(), 0.001); // 30 pix + 20 cash
        assertEquals(50.0, conciliacao.totalApiGeral(), 0.001);
        assertEquals(0.0, conciliacao.diferencaGeral(), 0.001);
    }
}
