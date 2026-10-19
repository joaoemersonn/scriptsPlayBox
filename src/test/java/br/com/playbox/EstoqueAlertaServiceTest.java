package br.com.playbox;

import br.com.playbox.client.WhatsAppSender;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.service.EstoqueAlertaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EstoqueAlertaServiceTest {

    private MaquinaRepository maquinaRepository;
    private WhatsAppSender whatsAppSender;
    private AppProperties properties;
    private EstoqueAlertaService alertaService;

    @BeforeEach
    void setUp() {
        maquinaRepository = mock(MaquinaRepository.class);
        whatsAppSender = mock(WhatsAppSender.class);
        properties = new AppProperties();
        properties.getWhatsapp().setEstoqueChatId("120363423381929568@g.us");

        alertaService = new EstoqueAlertaService(maquinaRepository, properties, whatsAppSender);
    }

    @Test
    @DisplayName("Deve enviar alerta consolidado para máquinas com estoque abaixo de 30")
    void testVerificarEEnviarAlertas() {
        Estabelecimento est = new Estabelecimento();
        est.setNome("Loja Centro");
        est.setCidade("Custódia");

        Maquina m1 = new Maquina();
        m1.setId(1L);
        m1.setNomeIntegracao("Posto 1");
        m1.setIntegracao("VENDPROMAX");
        m1.setExternalId("1001");
        m1.setEstoqueAtual(22);
        m1.setEstoqueMinimoAlerta(30);
        m1.setCapacidadeMaxima(100);
        m1.setEstabelecimento(est);

        when(maquinaRepository.findMaquinasComEstoqueBaixo()).thenReturn(List.of(m1));
        when(whatsAppSender.sendMessage(eq("120363423381929568@g.us"), any(String.class))).thenReturn(true);
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));

        int notificadas = alertaService.verificarEEnviarAlertas();

        assertEquals(1, notificadas);
        assertNotNull(m1.getUltimaNotificacaoEstoqueBaixo());

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsAppSender).sendMessage(eq("120363423381929568@g.us"), msgCaptor.capture());
        String msg = msgCaptor.getValue();
        assertTrue(msg.contains("ALERTA DE ESTOQUE BAIXO"));
        assertTrue(msg.contains("22 pelúcias"));
    }

    @Test
    @DisplayName("Deve notificar imediatamente quando estoque cair abaixo de 30")
    void testNotificarQuedaEstoque() {
        Maquina m = new Maquina();
        m.setId(2L);
        m.setNomeIntegracao("Máquina Shopping");
        m.setIntegracao("ZAPIZI");
        m.setExternalId("POSX-99");
        m.setEstoqueAtual(25);
        m.setEstoqueMinimoAlerta(30);
        m.setCapacidadeMaxima(100);

        when(whatsAppSender.sendMessage(eq("120363423381929568@g.us"), any(String.class))).thenReturn(true);
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));

        // Transição: antes estava 32 (>= 30) e agora caiu para 25 (< 30)
        boolean notificado = alertaService.notificarQuedaEstoque(m, 32, 25);

        assertTrue(notificado);
        assertNotNull(m.getUltimaNotificacaoEstoqueBaixo());
        verify(whatsAppSender).sendMessage(eq("120363423381929568@g.us"), any(String.class));
    }

    @Test
    @DisplayName("Não deve reenviar alerta se já foi notificada recentemente (< 24h)")
    void testNaoReenviarSeNotificadaRecentemente() {
        Maquina m = new Maquina();
        m.setId(3L);
        m.setEstoqueAtual(15);
        m.setEstoqueMinimoAlerta(30);
        m.setUltimaNotificacaoEstoqueBaixo(LocalDateTime.now().minusHours(2)); // notificada há 2 horas

        when(maquinaRepository.findMaquinasComEstoqueBaixo()).thenReturn(List.of(m));

        int notificadas = alertaService.verificarEEnviarAlertas();

        assertEquals(0, notificadas);
        verify(whatsAppSender, never()).sendMessage(anyString(), anyString());
    }
}
