package br.com.playbox.service;

import br.com.playbox.entity.AluguelRepasse;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.MeioPagamento;
import br.com.playbox.model.RelatorioOperacoesDTO;
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
    private final RelatorioOperacoesService relatorioOperacoesService;

    public AluguelService(
            EstabelecimentoRepository estabelecimentoRepository,
            AluguelRepasseRepository aluguelRepository,
            MeioPagamentoRepository meioPagamentoRepository,
            RelatorioOperacoesService relatorioOperacoesService
    ) {
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.aluguelRepository = aluguelRepository;
        this.meioPagamentoRepository = meioPagamentoRepository;
        this.relatorioOperacoesService = relatorioOperacoesService;
    }

    @Transactional
    public List<AluguelRepasse> gerarCobrancasMes(YearMonth anoMes) {
        // Assegura que estabelecimentos inativos não possuam cobranças pendentes
        aluguelRepository.deleteByEstabelecimentoAtivoFalseAndPagoFalse();

        String mesAnoStr = anoMes.toString(); // YYYY-MM
        List<Estabelecimento> estabelecimentos = estabelecimentoRepository.findByAtivoTrue();
        List<AluguelRepasse> criados = new ArrayList<>();

        for (Estabelecimento est : estabelecimentos) {
            boolean isDinamico = est.isAluguelDinamico();
            if (!isDinamico && (est.getValorAcordado() == null || est.getValorAcordado() <= 0)) {
                continue;
            }

            Optional<AluguelRepasse> existente = aluguelRepository.findByEstabelecimentoIdAndMesAno(est.getId(), mesAnoStr);
            if (existente.isPresent()) {
                AluguelRepasse aluguel = existente.get();
                // Se for aluguel dinâmico em aberto e sem valor manual, recalcula com os dados acumulados do mês
                if (isDinamico && !aluguel.isPago() && !Boolean.TRUE.equals(aluguel.getValorManual())) {
                    atualizarValorDinamico(aluguel, anoMes);
                }
                continue;
            }

            int dia = extrairDiaVencimento(est.getDiaPagamento());
            int maxDiaMes = anoMes.lengthOfMonth();
            int diaEfetivo = Math.min(dia, maxDiaMes);
            LocalDate dataVencimento = anoMes.atDay(diaEfetivo);

            Double valorAluguel = 0.0;
            Double lucroCalc = null;
            Double pctRepasse = null;
            String tipo = isDinamico ? "DINAMICO" : "FIXO";

            if (isDinamico) {
                pctRepasse = est.getPercentualRepasse() != null ? est.getPercentualRepasse() : 25.0;
                YearMonth mesFechado = anoMes.minusMonths(1);
                LocalDate inicioMes = mesFechado.atDay(1);
                LocalDate fimMes = mesFechado.atEndOfMonth();
                try {
                    RelatorioOperacoesDTO dto = relatorioOperacoesService.compilarRelatorio(
                            est.getId(), null, null, inicioMes, fimMes,
                            19.90, 4.99, 69.90, 350.00, pctRepasse, null
                    );
                    lucroCalc = dto.getLucroTotal();
                    valorAluguel = Math.max(0.0, Math.round(dto.getValorRepasse() * 100.0) / 100.0);
                } catch (Exception e) {
                    log.warn("Falha ao calcular repasse dinâmico para {} em {}: {}", est.getNome(), mesAnoStr, e.getMessage());
                    valorAluguel = est.getValorAcordado() != null ? est.getValorAcordado() : 0.0;
                }
            } else {
                valorAluguel = est.getValorAcordado();
            }

            AluguelRepasse aluguel = new AluguelRepasse(est, mesAnoStr, dataVencimento, valorAluguel);
            aluguel.setTipoAluguel(tipo);
            aluguel.setMesCompetencia(isDinamico ? anoMes.minusMonths(1).toString() : null);
            aluguel.setLucroCalculado(lucroCalc);
            aluguel.setPercentualRepasse(pctRepasse);
            aluguel.setValorManual(false);

            criados.add(aluguelRepository.save(aluguel));
        }

        log.info("Gerados {} novos registros de repasse/aluguel para o mês {}", criados.size(), mesAnoStr);
        return criados;
    }

    private void atualizarValorDinamico(AluguelRepasse aluguel, YearMonth anoMes) {
        Estabelecimento est = aluguel.getEstabelecimento();
        Double pctRepasse = est.getPercentualRepasse() != null ? est.getPercentualRepasse() : 25.0;
        YearMonth mesFechado = anoMes.minusMonths(1);
        LocalDate inicioMes = mesFechado.atDay(1);
        LocalDate fimMes = mesFechado.atEndOfMonth();
        try {
            RelatorioOperacoesDTO dto = relatorioOperacoesService.compilarRelatorio(
                    est.getId(), null, null, inicioMes, fimMes,
                    19.90, 4.99, 69.90, 350.00, pctRepasse, null
            );
            aluguel.setLucroCalculado(dto.getLucroTotal());
            aluguel.setPercentualRepasse(pctRepasse);
            aluguel.setTipoAluguel("DINAMICO");
            aluguel.setMesCompetencia(mesFechado.toString());
            aluguel.setValor(Math.max(0.0, Math.round(dto.getValorRepasse() * 100.0) / 100.0));
            aluguelRepository.save(aluguel);
        } catch (Exception e) {
            log.warn("Falha ao atualizar repasse dinâmico de {} em {}: {}", est.getNome(), anoMes, e.getMessage());
        }
    }

    @Transactional
    public AluguelRepasse calcularAluguelDinamico(
            Long aluguelId,
            Double valorUnitarioPelucia,
            Double taxaMaquininha,
            Double telemetria,
            Double visitas,
            Double percentualRepasse
    ) {
        AluguelRepasse aluguel = aluguelRepository.findById(aluguelId)
                .orElseThrow(() -> new IllegalArgumentException("Aluguel não encontrado: " + aluguelId));

        Estabelecimento est = aluguel.getEstabelecimento();
        YearMonth ym = YearMonth.parse(aluguel.getMesAno());
        // O aluguel a pagar no mês (ex: 2026-10) baseia-se nas operações do mês fechado anterior (ex: 2026-09)
        YearMonth mesFechado = ym.minusMonths(1);
        LocalDate inicioMes = mesFechado.atDay(1);
        LocalDate fimMes = mesFechado.atEndOfMonth();

        double pct = percentualRepasse != null ? percentualRepasse
                : (est.getPercentualRepasse() != null ? est.getPercentualRepasse() : 25.0);
        double unitPelucia = valorUnitarioPelucia != null ? valorUnitarioPelucia : 19.90;
        double taxaMq = taxaMaquininha != null ? taxaMaquininha : 4.99;
        double telem = telemetria != null ? telemetria : 69.90;
        double vis = visitas != null ? visitas : 350.00;

        RelatorioOperacoesDTO dto = relatorioOperacoesService.compilarRelatorio(
                est.getId(), null, null, inicioMes, fimMes,
                unitPelucia, taxaMq, telem, vis, pct, null
        );

        double valorCalculado = Math.max(0.0, Math.round(dto.getValorRepasse() * 100.0) / 100.0);
        aluguel.setValor(valorCalculado);
        aluguel.setTipoAluguel("DINAMICO");
        aluguel.setMesCompetencia(mesFechado.toString());
        aluguel.setLucroCalculado(dto.getLucroTotal());
        aluguel.setPercentualRepasse(pct);
        aluguel.setValorManual(false);

        return aluguelRepository.save(aluguel);
    }

    @Transactional
    public List<AluguelRepasse> calcularAlugueisDinamicosMes(YearMonth anoMes) {
        String mesAnoStr = anoMes.toString();
        List<AluguelRepasse> doMes = aluguelRepository.findByMesAno(mesAnoStr);
        List<AluguelRepasse> atualizados = new ArrayList<>();

        for (AluguelRepasse a : doMes) {
            if (a.getEstabelecimento().isAluguelDinamico() && !Boolean.TRUE.equals(a.getValorManual())) {
                atualizados.add(calcularAluguelDinamico(a.getId(), null, null, null, null, null));
            }
        }
        return atualizados;
    }

    @Transactional
    public AluguelRepasse definirValorAluguel(Long aluguelId, Double novoValor, String obs) {
        if (novoValor == null || novoValor < 0) {
            throw new IllegalArgumentException("O valor do aluguel deve ser maior ou igual a zero");
        }
        AluguelRepasse aluguel = aluguelRepository.findById(aluguelId)
                .orElseThrow(() -> new IllegalArgumentException("Aluguel não encontrado: " + aluguelId));

        aluguel.setValor(novoValor);
        aluguel.setValorManual(true);
        if (obs != null && !obs.isBlank()) {
            aluguel.setComprovanteOuObs(obs);
        }
        return aluguelRepository.save(aluguel);
    }

    @Transactional
    public AluguelRepasse definirValorAluguelPorEstabelecimento(Long estabelecimentoId, YearMonth anoMes, Double novoValor, String obs) {
        if (novoValor == null || novoValor < 0) {
            throw new IllegalArgumentException("O valor do aluguel deve ser maior ou igual a zero");
        }
        String mesAnoStr = anoMes.toString();
        AluguelRepasse aluguel = aluguelRepository.findByEstabelecimentoIdAndMesAno(estabelecimentoId, mesAnoStr)
                .orElseGet(() -> {
                    Estabelecimento est = estabelecimentoRepository.findById(estabelecimentoId)
                            .orElseThrow(() -> new IllegalArgumentException("Estabelecimento não encontrado: " + estabelecimentoId));
                    int dia = extrairDiaVencimento(est.getDiaPagamento());
                    int maxDiaMes = anoMes.lengthOfMonth();
                    int diaEfetivo = Math.min(dia, maxDiaMes);
                    LocalDate dataVencimento = anoMes.atDay(diaEfetivo);
                    AluguelRepasse novo = new AluguelRepasse(est, mesAnoStr, dataVencimento, novoValor);
                    novo.setTipoAluguel(est.isAluguelDinamico() ? "DINAMICO" : "FIXO");
                    return novo;
                });

        aluguel.setValor(novoValor);
        aluguel.setValorManual(true);
        if (obs != null && !obs.isBlank()) {
            aluguel.setComprovanteOuObs(obs);
        }
        return aluguelRepository.save(aluguel);
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
