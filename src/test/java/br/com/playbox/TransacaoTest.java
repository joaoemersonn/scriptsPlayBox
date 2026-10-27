package br.com.playbox;

import br.com.playbox.entity.Transacao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransacaoTest {

    @Test
    void testTransacaoNormalNaoTeste() {
        Transacao t = new Transacao();
        t.setValor(150.0);
        t.setValorCash(100.0);
        t.setValorPix(50.0);
        t.setQuantidadePremios(3.0);
        t.setValorEstorno(10.0);
        t.setTeste(false);

        assertEquals(150.0, t.getValorEfetivo());
        assertEquals(100.0, t.getValorCashEfetivo());
        assertEquals(50.0, t.getValorPixEfetivo());
        assertEquals(3.0, t.getQuantidadePremiosEfetiva());
        assertEquals(10.0, t.getValorEstornoEfetivo());
        assertEquals(0.0, t.getValorTesteDesconsiderado());
    }

    @Test
    void testTransacaoCemPorCentoTeste() {
        Transacao t = new Transacao();
        t.setValor(200.0);
        t.setValorCash(150.0);
        t.setValorPix(50.0);
        t.setQuantidadePremios(5.0);
        t.setValorEstorno(0.0);
        t.setTeste(true);
        t.setValorTeste(0.0); // 100% teste
        t.setMotivoTeste("Testes de bancada antes de ir para a loja");

        assertEquals(0.0, t.getValorEfetivo(), "Valor efetivo deve ser 0 para transação 100% teste");
        assertEquals(0.0, t.getValorCashEfetivo(), "Cash efetivo deve ser 0 para transação 100% teste");
        assertEquals(0.0, t.getValorPixEfetivo());
        assertEquals(0.0, t.getQuantidadePremiosEfetiva());
        assertEquals(200.0, t.getValorTesteDesconsiderado());
        assertEquals("Testes de bancada antes de ir para a loja", t.getMotivoTeste());
    }

    @Test
    void testTransacaoComValorTesteParcial() {
        Transacao t = new Transacao();
        t.setValor(100.0);
        t.setValorCash(80.0);
        t.setValorPix(20.0);
        t.setQuantidadePremios(2.0);
        t.setTeste(true);
        t.setValorTeste(30.0); // R$ 30 foi teste da equipe
        t.setMotivoTeste("3 jogadas de teste de garra");

        assertEquals(70.0, t.getValorEfetivo(), "Faturamento deve deduzir R$ 30");
        assertEquals(50.0, t.getValorCashEfetivo(), "Cash da máquina deve deduzir R$ 30 do cofre");
        assertEquals(20.0, t.getValorPixEfetivo());
        assertEquals(2.0, t.getQuantidadePremiosEfetiva());
        assertEquals(30.0, t.getValorTesteDesconsiderado());
    }

    @Test
    void testTransacaoValorTesteMaiorQueBruto() {
        Transacao t = new Transacao();
        t.setValor(50.0);
        t.setValorCash(50.0);
        t.setTeste(true);
        t.setValorTeste(80.0); // maior que o total da transação

        assertEquals(0.0, t.getValorEfetivo(), "Não pode ficar negativo");
        assertEquals(0.0, t.getValorCashEfetivo(), "Não pode ficar negativo");
        assertEquals(50.0, t.getValorTesteDesconsiderado(), "Limitado ao bruto");
    }
}
