package br.com.playbox.service;

import br.com.playbox.entity.AluguelRepasse;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.MeioPagamento;
import br.com.playbox.repository.AluguelRepasseRepository;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MeioPagamentoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AluguelService {

    private static final Logger log = LoggerFactory.getLogger(AluguelService.class);
    private static final Pattern DIA_PATTERN = Pattern.compile("(\\d{1,2})");

    private final EstabelecimentoRepository estabelecimentoRepository;
    private final AluguelRepasseRepository aluguelRepository;
    private final MeioPagamentoRepository meioPagamentoRepository;

    public AluguelService(
            EstabelecimentoRepository estabelecimentoRepository,
            AluguelRepasseRepository aluguelRepository,
            MeioPagamentoRepository meioPagamentoRepository
    ) {
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.aluguelRepository = aluguelRepository;
        this.meioPagamentoRepository = meioPagamentoRepository;
    }

    @Transactional
    public List<AluguelRepasse> gerarCobrancasMes(YearMonth anoMes) {
        // Assegura que estabelecimentos inativos não possuam cobranças pendentes
        aluguelRepository.deleteByEstabelecimentoAtivoFalseAndPagoFalse();

        String mesAnoStr = anoMes.toString(); // YYYY-MM
        List<Estabelecimento> estabelecimentos = estabelecimentoRepository.findByAtivoTrue();
        List<AluguelRepasse> criados = new ArrayList<>();

        for (Estabelecimento est : estabelecimentos) {
            if (!est.isAtivo() || est.getValorAcordado() == null || est.getValorAcordado() <= 0) {
                continue;
            }

            Optional<AluguelRepasse> existente = aluguelRepository.findByEstabelecimentoIdAndMesAno(est.getId(), mesAnoStr);
            if (existente.isPresent()) {
                continue;
            }

            int dia = extrairDiaVencimento(est.getDiaPagamento());
            int maxDiaMes = anoMes.lengthOfMonth();
            int diaEfetivo = Math.min(dia, maxDiaMes);
            LocalDate dataVencimento = anoMes.atDay(diaEfetivo);

            AluguelRepasse aluguel = new AluguelRepasse(est, mesAnoStr, dataVencimento, est.getValorAcordado());
            criados.add(aluguelRepository.save(aluguel));
        }

        log.info("Gerados {} novos registros de repasse/aluguel para o mês {}", criados.size(), mesAnoStr);
        return criados;
    }

    @Transactional
    public void removerCobrancasPendentesDeInativos() {
        aluguelRepository.deleteByEstabelecimentoAtivoFalseAndPagoFalse();
    }

    public List<AluguelRepasse> listarVencendoAte(int diasAFrente) {
        LocalDate limite = LocalDate.now().plusDays(diasAFrente);
        return aluguelRepository.findVencendoAte(limite);
    }

    public List<AluguelRepasse> listarPendentes() {
        return aluguelRepository.findByPagoFalseOrderByDataVencimentoAsc();
    }

    public List<AluguelRepasse> listarPagos() {
        return aluguelRepository.findByPagoTrueOrderByDataPagamentoDesc();
    }

    @Transactional
    public AluguelRepasse registrarPagamento(Long aluguelId, LocalDate dataPagamento, String meioUtilizado, String obs) {
        AluguelRepasse aluguel = aluguelRepository.findById(aluguelId)
                .orElseThrow(() -> new IllegalArgumentException("Aluguel não encontrado: " + aluguelId));

        aluguel.setPago(true);
        aluguel.setDataPagamento(dataPagamento != null ? dataPagamento : LocalDate.now());
        aluguel.setMeioPagamentoUtilizado(meioUtilizado);
        aluguel.setComprovanteOuObs(obs);

        return aluguelRepository.save(aluguel);
    }

    @Transactional
    public AluguelRepasse estornarPagamento(Long aluguelId) {
        AluguelRepasse aluguel = aluguelRepository.findById(aluguelId)
                .orElseThrow(() -> new IllegalArgumentException("Aluguel não encontrado: " + aluguelId));

        aluguel.setPago(false);
        aluguel.setDataPagamento(null);
        aluguel.setComprovanteOuObs(null);

        return aluguelRepository.save(aluguel);
    }

    public static int extrairDiaVencimento(String diaPagamento) {
        if (diaPagamento == null || diaPagamento.isBlank()) {
            return 10; // Padrão
        }
        Matcher matcher = DIA_PATTERN.matcher(diaPagamento);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {}
        }
        return 10;
    }
}
