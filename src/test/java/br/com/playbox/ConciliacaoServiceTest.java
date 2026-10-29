package br.com.playbox;

import br.com.playbox.client.PayXYZClient;
import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.dto.ConciliacaoDTO;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.ConciliacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ConciliacaoServiceTest {

    private MaquinaRepository maquinaRepository;
    private TransacaoRepository transacaoRepository;
    private VendProMaxClient vendClient;
    private PayXYZClient payxyzClient;
    private ZapiziClient zapiziClient;
    private AppProperties properties;
    private ConciliacaoService conciliacaoService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        maquinaRepository = Mockito.mock(MaquinaRepository.class);
        transacaoRepository = Mockito.mock(TransacaoRepository.class);
        vendClient = Mockito.mock(VendProMaxClient.class);
        payxyzClient = Mockito.mock(PayXYZClient.class);
        zapiziClient = Mockito.mock(ZapiziClient.class);
        properties = new AppProperties();
        properties.getVendpromax().setEmail("test@vendpromax.com");
        properties.getVendpromax().setPassword("pass");

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
    void testConciliacaoValoresBatem() throws Exception {
        Maquina m1 = new Maquina();
        m1.setId(1L);
        m1.setNomeIntegracao("Grua VendProMax");
        m1.setExternalId("76847412");
        m1.setIntegracao("VENDPROMAX");

        Estabelecimento e1 = new Estabelecimento();
        e1.setId(10L);
        e1.setNome("Posto Central");
        m1.setEstabelecimento(e1);

        when(maquinaRepository.findAll()).thenReturn(List.of(m1));

        // Banco: 2 pagamentos de R$ 10 (Total: 20)
        Transacao t1 = new Transacao("TX1", "VENDPROMAX", m1, e1, LocalDateTime.of(2026, 10, 27, 10, 0), "PIX", 10.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0);
        Transacao t2 = new Transacao("TX2", "VENDPROMAX", m1, e1, LocalDateTime.of(2026, 10, 27, 11, 0), "CASH", 10.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        when(transacaoRepository.findByPeriodAndFilters(any(), any(), eq(1L), any()))
                .thenReturn(List.of(t1, t2));

        // Mock API VendProMax retornando os mesmos pagamentos (total: 20)
        String apiJson = """
            {
              "total": 20.0,
              "estornos": 0.0,
              "pagamentos": [
                {"id": "p1", "valor": 10.0, "tipo": "PIX", "estornado": false},
                {"id": "p2", "valor": 10.0, "tipo": "CASH", "estornado": false}
              ]
            }
        """;
        when(vendClient.login(any(), any())).thenReturn("token123");
        when(vendClient.getPagamentosPeriodo(any(), eq("76847412"), any(), any()))
                .thenReturn(objectMapper.readTree(apiJson));

        ConciliacaoDTO result = conciliacaoService.conciliar(
                LocalDate.of(2026, 10, 27),
                LocalDate.of(2026, 10, 27),
                null,
                null,
                null
        );

        assertNotNull(result);
        assertEquals("CONCILIADO", result.statusGeral());
        assertEquals(20.0, result.totalBancoGeral());
        assertEquals(20.0, result.totalApiGeral());
        assertEquals(0.0, result.diferencaGeral());
        assertEquals(1, result.totalConciliadas());
        assertEquals(0, result.totalDivergentes());

        ConciliacaoDTO.ItemConciliacaoDTO item = result.itens().get(0);
        assertEquals("CONCILIADO", item.status());
        assertEquals(20.0, item.totalBanco());
        assertEquals(20.0, item.totalApi());
        assertEquals(0.0, item.diferenca());
        assertEquals(10.0, item.pixBanco());
        assertEquals(10.0, item.cashBanco());
        assertEquals(2, item.qtdTransacoesBanco());
    }

    @Test
    void testConciliacaoDetectaDivergencia() throws Exception {
        Maquina m1 = new Maquina();
        m1.setId(2L);
        m1.setNomeIntegracao("Grua VendProMax");
        m1.setExternalId("76847412");
        m1.setIntegracao("VENDPROMAX");

        when(maquinaRepository.findAll()).thenReturn(List.of(m1));

        // Banco: 1 pagamento de R$ 10
        Transacao t1 = new Transacao("TX1", "VENDPROMAX", m1, null, LocalDateTime.of(2026, 10, 27, 10, 0), "PIX", 10.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0);
        when(transacaoRepository.findByPeriodAndFilters(any(), any(), eq(2L), any()))
                .thenReturn(List.of(t1));

        // Mock API VendProMax retornando R$ 30 (faltam R$ 20 no banco)
        String apiJson = """
            {
              "total": 30.0,
              "estornos": 0.0,
              "pagamentos": [
                {"id": "p1", "valor": 10.0, "tipo": "PIX", "estornado": false},
                {"id": "p2", "valor": 20.0, "tipo": "CASH", "estornado": false}
              ]
            }
        """;
        when(vendClient.login(any(), any())).thenReturn("token123");
        when(vendClient.getPagamentosPeriodo(any(), eq("76847412"), any(), any()))
                .thenReturn(objectMapper.readTree(apiJson));

        ConciliacaoDTO result = conciliacaoService.conciliar(
                LocalDate.of(2026, 10, 27),
                LocalDate.of(2026, 10, 27),
                null,
                null,
                null
        );

        assertNotNull(result);
        assertEquals("DIVERGENTE", result.statusGeral());
        assertEquals(10.0, result.totalBancoGeral());
        assertEquals(30.0, result.totalApiGeral());
        assertEquals(-20.0, result.diferencaGeral());
        assertEquals(0, result.totalConciliadas());
        assertEquals(1, result.totalDivergentes());
    }
}
