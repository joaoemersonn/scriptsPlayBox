package br.com.playbox.service;

import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.model.MachineReport;
import br.com.playbox.repository.TransacaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RelatorioService {

    private static final Logger log = LoggerFactory.getLogger(RelatorioService.class);
    private static final ZoneId ZONE_ID = ZoneId.of("America/Sao_Paulo");

    private final TransacaoRepository transacaoRepository;
    private final TransactionImportService transactionImportService;

    public RelatorioService(
            TransacaoRepository transacaoRepository,
            TransactionImportService transactionImportService
    ) {
        this.transacaoRepository = transacaoRepository;
        this.transactionImportService = transactionImportService;
    }

    public record PeriodoCalculado(String periodo, String titulo, LocalDateTime inicio, LocalDateTime fim) {}

    public PeriodoCalculado calcularPeriodo(String periodo, LocalDate dataInicio, LocalDate dataFim) {
        DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        LocalDate hoje = LocalDate.now(ZONE_ID);
        LocalDate ontem = hoje.minusDays(1);

        if (dataInicio != null || dataFim != null) {
            LocalDate start = dataInicio != null ? dataInicio : (dataFim != null ? dataFim : hoje);
            LocalDate end = dataFim != null ? dataFim : start;
            if (start.isAfter(end)) {
                LocalDate temp = start;
                start = end;
                end = temp;
            }
            LocalDateTime dtInicio = start.atStartOfDay();
            LocalDateTime dtFim = end.atTime(LocalTime.MAX);
            String titulo = "Relatório de " + start.format(dmy) + " até " + end.format(dmy);
            return new PeriodoCalculado("custom", titulo, dtInicio, dtFim);
        }

        String p = periodo != null ? periodo.trim().toLowerCase() : "mes";

        if ("24h-anterior".equals(p) || "24h".equals(p) || "ontem".equals(p)) {
            LocalDateTime dtInicio = ontem.atStartOfDay();
            LocalDateTime dtFim = ontem.atTime(LocalTime.MAX);
            String titulo = "Relatório de " + ontem.format(dmy);
            return new PeriodoCalculado("24h-anterior", titulo, dtInicio, dtFim);
        }

        if ("12h-atual".equals(p) || "12h".equals(p)) {
            LocalDateTime agora = LocalDateTime.now(ZONE_ID);
            LocalDateTime dtInicio = agora.minusHours(12);
            String titulo = "Relatório das Últimas 12h (" + hoje.format(dmy) + ")";
            return new PeriodoCalculado("12h-atual", titulo, dtInicio, agora);
        }

        if ("hoje".equals(p)) {
            LocalDateTime dtInicio = hoje.atStartOfDay();
            LocalDateTime dtFim = hoje.atTime(LocalTime.MAX);
            String titulo = "Relatório de Hoje (" + hoje.format(dmy) + ")";
            return new PeriodoCalculado("hoje", titulo, dtInicio, dtFim);
        }

        if ("7d".equals(p) || "7dias".equals(p) || "ultimos-7-dias".equals(p)) {
            LocalDate inicio7d = hoje.minusDays(6);
            LocalDateTime dtInicio = inicio7d.atStartOfDay();
            LocalDateTime dtFim = hoje.atTime(LocalTime.MAX);
            String titulo = "Relatório dos Últimos 7 Dias (" + inicio7d.format(dmy) + " a " + hoje.format(dmy) + ")";
            return new PeriodoCalculado("7d", titulo, dtInicio, dtFim);
        }

        if ("30d".equals(p) || "30dias".equals(p) || "ultimos-30-dias".equals(p)) {
            LocalDate inicio30d = hoje.minusDays(29);
            LocalDateTime dtInicio = inicio30d.atStartOfDay();
            LocalDateTime dtFim = hoje.atTime(LocalTime.MAX);
            String titulo = "Relatório dos Últimos 30 Dias (" + inicio30d.format(dmy) + " a " + hoje.format(dmy) + ")";
            return new PeriodoCalculado("30d", titulo, dtInicio, dtFim);
        }

        // Default: mês atual
        LocalDate primeiroDiaMes = hoje.withDayOfMonth(1);
        LocalDateTime dtInicio = primeiroDiaMes.atStartOfDay();
        LocalDateTime dtFim = hoje.atTime(LocalTime.MAX);
        String mesNome = ReportGeneratorService.getMonthNamePt(hoje.getMonthValue());
        String titulo = "Relatório do Mês de " + mesNome + " (" + hoje.format(DateTimeFormatter.ofPattern("MM/yyyy")) + ")";
        return new PeriodoCalculado("mes", titulo, dtInicio, dtFim);
    }

    public Map<String, Object> gerarRelatorioJson(
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Long estabelecimentoId,
            String cidade,
            Long maquinaId,
            String integracao,
            boolean sincronizar
    ) {
        PeriodoCalculado p = calcularPeriodo(periodo, dataInicio, dataFim);

        if (sincronizar) {
            log.info("Sincronização forçada solicitada para o período {} a {}", p.inicio().toLocalDate(), p.fim().toLocalDate());
            transactionImportService.importTransactionsRange(p.inicio().toLocalDate(), p.fim().toLocalDate());
        } else {
            transactionImportService.ensureTransactionsImported(p.inicio().toLocalDate(), p.fim().toLocalDate());
        }

        List<Transacao> transacoes = transacaoRepository.findFiltered(
                p.inicio(),
                p.fim(),
                estabelecimentoId,
                cidade != null && !cidade.isBlank() ? cidade.trim() : null,
                maquinaId,
                integracao != null && !integracao.isBlank() ? integracao.trim() : null
        );

        return processarRelatorio(p, transacoes, estabelecimentoId, cidade, maquinaId, integracao);
    }

    public List<MachineReport> getMachineReportsFromDatabase(
            LocalDateTime inicio,
            LocalDateTime fim,
            Long estabelecimentoId,
            String cidade,
            Long maquinaId,
            String integracao
    ) {
        List<Transacao> transacoes = transacaoRepository.findFiltered(inicio, fim, estabelecimentoId, cidade, maquinaId, integracao);

        Map<String, MachineReport> reportMap = new LinkedHashMap<>();

        for (Transacao t : transacoes) {
            String key;
            Object idMaquina;
            String nomeMaquina;

            if (t.getMaquina() != null) {
                idMaquina = t.getMaquina().getId();
                nomeMaquina = t.getMaquina().getNomeIntegracao() != null ? t.getMaquina().getNomeIntegracao() : "Máquina #" + t.getMaquina().getId();
                key = "MAQ_" + idMaquina;
            } else {
                idMaquina = t.getExternalTxId();
                nomeMaquina = t.getExternalTxId();
                key = "EXT_" + idMaquina;
            }

            MachineReport rep = reportMap.computeIfAbsent(key, k -> new MachineReport(idMaquina, nomeMaquina));
            rep.setValorEstornos(rep.getValorEstornos() + (t.getValorEstorno() != null ? t.getValorEstorno() : 0.0));
            rep.setValorEspecie(rep.getValorEspecie() + (t.getValorCash() != null ? t.getValorCash() : 0.0));
            rep.setValorPix(rep.getValorPix() + (t.getValorPix() != null ? t.getValorPix() : 0.0));
            rep.setValorCredito(rep.getValorCredito() + (t.getValorCredito() != null ? t.getValorCredito() : 0.0));
            rep.setValorDebito(rep.getValorDebito() + (t.getValorDebito() != null ? t.getValorDebito() : 0.0));
            rep.setQuantidadePremios(rep.getQuantidadePremios() + (t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0));
            rep.updateTotals();
        }

        List<MachineReport> result = new ArrayList<>(reportMap.values());
        result.sort(Comparator.comparing(MachineReport::getNomeMaquina, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    private Map<String, Object> processarRelatorio(
            PeriodoCalculado p,
            List<Transacao> transacoes,
            Long filtroEstabelecimentoId,
            String filtroCidade,
            Long filtroMaquinaId,
            String filtroIntegracao
    ) {
        double faturamentoBruto = 0.0;
        double totalEstornos = 0.0;
        double totalCash = 0.0;
        double totalPix = 0.0;
        double totalCredito = 0.0;
        double totalDebito = 0.0;
        double totalPremios = 0.0;

        for (Transacao t : transacoes) {
            faturamentoBruto += (t.getValor() != null ? t.getValor() : 0.0);
            totalEstornos += (t.getValorEstorno() != null ? t.getValorEstorno() : 0.0);
            totalCash += (t.getValorCash() != null ? t.getValorCash() : 0.0);
            totalPix += (t.getValorPix() != null ? t.getValorPix() : 0.0);
            totalCredito += (t.getValorCredito() != null ? t.getValorCredito() : 0.0);
            totalDebito += (t.getValorDebito() != null ? t.getValorDebito() : 0.0);
            totalPremios += (t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0);
        }

        double faturamentoLiquido = round(faturamentoBruto - totalEstornos);
        double receitaPorPremio = totalPremios > 0 ? round(faturamentoBruto / totalPremios) : 0.0;

        Map<String, Object> resumoGeral = new LinkedHashMap<>();
        resumoGeral.put("faturamentoBruto", round(faturamentoBruto));
        resumoGeral.put("totalEstornos", round(totalEstornos));
        resumoGeral.put("faturamentoLiquido", faturamentoLiquido);
        resumoGeral.put("quantidadePremios", round(totalPremios));
        resumoGeral.put("receitaPorPremioLiberado", receitaPorPremio);
        resumoGeral.put("totalRegistros", transacoes.size());

        Map<String, Double> formas = new LinkedHashMap<>();
        formas.put("especie", round(totalCash));
        formas.put("pix", round(totalPix));
        formas.put("credito", round(totalCredito));
        formas.put("debito", round(totalDebito));
        resumoGeral.put("formasPagamento", formas);

        // Agrupamento por Estabelecimento
        Map<String, List<Transacao>> porEstabelecimentoGroup = transacoes.stream()
                .collect(Collectors.groupingBy(t -> {
                    if (t.getEstabelecimento() != null) return "EST_" + t.getEstabelecimento().getId();
                    return "EST_NONE";
                }));

        List<Map<String, Object>> listaEstabelecimentos = new ArrayList<>();
        for (List<Transacao> txList : porEstabelecimentoGroup.values()) {
            if (txList.isEmpty()) continue;
            Transacao first = txList.get(0);
            Estabelecimento est = first.getEstabelecimento();

            double estBruto = txList.stream().mapToDouble(t -> t.getValor() != null ? t.getValor() : 0.0).sum();
            double estEstornos = txList.stream().mapToDouble(t -> t.getValorEstorno() != null ? t.getValorEstorno() : 0.0).sum();
            double estPremios = txList.stream().mapToDouble(t -> t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0).sum();
            double estCash = txList.stream().mapToDouble(t -> t.getValorCash() != null ? t.getValorCash() : 0.0).sum();
            double estPix = txList.stream().mapToDouble(t -> t.getValorPix() != null ? t.getValorPix() : 0.0).sum();
            double estCredito = txList.stream().mapToDouble(t -> t.getValorCredito() != null ? t.getValorCredito() : 0.0).sum();
            double estDebito = txList.stream().mapToDouble(t -> t.getValorDebito() != null ? t.getValorDebito() : 0.0).sum();
            long maquinasCount = txList.stream().map(t -> t.getMaquina() != null ? t.getMaquina().getId() : null).filter(Objects::nonNull).distinct().count();

            Map<String, Object> itemEst = new LinkedHashMap<>();
            itemEst.put("estabelecimentoId", est != null ? est.getId() : null);
            itemEst.put("nome", est != null ? est.getNome() : "Sem Estabelecimento");
            itemEst.put("cidade", est != null ? est.getCidade() : "");
            itemEst.put("responsavel", est != null ? est.getResponsavel() : "");
            itemEst.put("quantidadeMaquinas", maquinasCount);
            itemEst.put("faturamentoBruto", round(estBruto));
            itemEst.put("totalEstornos", round(estEstornos));
            itemEst.put("faturamentoLiquido", round(estBruto - estEstornos));
            itemEst.put("quantidadePremios", round(estPremios));
            itemEst.put("receitaPorPremio", estPremios > 0 ? round(estBruto / estPremios) : 0.0);

            Map<String, Double> estFormas = new LinkedHashMap<>();
            estFormas.put("especie", round(estCash));
            estFormas.put("pix", round(estPix));
            estFormas.put("credito", round(estCredito));
            estFormas.put("debito", round(estDebito));
            itemEst.put("formasPagamento", estFormas);

            listaEstabelecimentos.add(itemEst);
        }
        listaEstabelecimentos.sort((a, b) -> Double.compare((double) b.get("faturamentoBruto"), (double) a.get("faturamentoBruto")));

        // Agrupamento por Máquina
        Map<String, List<Transacao>> porMaquinaGroup = transacoes.stream()
                .collect(Collectors.groupingBy(t -> {
                    if (t.getMaquina() != null) return "MAQ_" + t.getMaquina().getId();
                    return "TX_" + t.getExternalTxId();
                }));

        List<Map<String, Object>> listaMaquinas = new ArrayList<>();
        for (List<Transacao> txList : porMaquinaGroup.values()) {
            if (txList.isEmpty()) continue;
            Transacao first = txList.get(0);
            Maquina maq = first.getMaquina();
            Estabelecimento est = first.getEstabelecimento() != null ? first.getEstabelecimento() : (maq != null ? maq.getEstabelecimento() : null);

            double maqBruto = txList.stream().mapToDouble(t -> t.getValor() != null ? t.getValor() : 0.0).sum();
            double maqEstornos = txList.stream().mapToDouble(t -> t.getValorEstorno() != null ? t.getValorEstorno() : 0.0).sum();
            double maqPremios = txList.stream().mapToDouble(t -> t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0).sum();
            double maqCash = txList.stream().mapToDouble(t -> t.getValorCash() != null ? t.getValorCash() : 0.0).sum();
            double maqPix = txList.stream().mapToDouble(t -> t.getValorPix() != null ? t.getValorPix() : 0.0).sum();
            double maqCredito = txList.stream().mapToDouble(t -> t.getValorCredito() != null ? t.getValorCredito() : 0.0).sum();
            double maqDebito = txList.stream().mapToDouble(t -> t.getValorDebito() != null ? t.getValorDebito() : 0.0).sum();

            Map<String, Object> itemMaq = new LinkedHashMap<>();
            itemMaq.put("maquinaId", maq != null ? maq.getId() : null);
            itemMaq.put("numeroPlanilha", maq != null ? maq.getNumeroPlanilha() : null);
            itemMaq.put("nomeMaquina", maq != null && maq.getNomeIntegracao() != null ? maq.getNomeIntegracao() : first.getExternalTxId());
            itemMaq.put("externalId", maq != null ? maq.getExternalId() : "");
            itemMaq.put("integracao", first.getIntegracao());
            itemMaq.put("estabelecimentoId", est != null ? est.getId() : null);
            itemMaq.put("estabelecimentoNome", est != null ? est.getNome() : "Sem Estabelecimento");
            itemMaq.put("cidade", est != null ? est.getCidade() : "");
            itemMaq.put("faturamentoBruto", round(maqBruto));
            itemMaq.put("totalEstornos", round(maqEstornos));
            itemMaq.put("faturamentoLiquido", round(maqBruto - maqEstornos));
            itemMaq.put("quantidadePremios", round(maqPremios));
            itemMaq.put("receitaPorPremio", maqPremios > 0 ? round(maqBruto / maqPremios) : 0.0);

            Map<String, Double> maqFormas = new LinkedHashMap<>();
            maqFormas.put("especie", round(maqCash));
            maqFormas.put("pix", round(maqPix));
            maqFormas.put("credito", round(maqCredito));
            maqFormas.put("debito", round(maqDebito));
            itemMaq.put("formasPagamento", maqFormas);

            listaMaquinas.add(itemMaq);
        }
        listaMaquinas.sort((a, b) -> Double.compare((double) b.get("faturamentoBruto"), (double) a.get("faturamentoBruto")));

        // Agrupamento por Dia (Evolução Temporal para gráficos)
        Map<LocalDate, List<Transacao>> porDiaGroup = transacoes.stream()
                .filter(t -> t.getTimestamp() != null)
                .collect(Collectors.groupingBy(t -> t.getTimestamp().toLocalDate(), TreeMap::new, Collectors.toList()));

        List<Map<String, Object>> listaDias = new ArrayList<>();
        DateTimeFormatter dmyShort = DateTimeFormatter.ofPattern("dd/MM");
        for (Map.Entry<LocalDate, List<Transacao>> entry : porDiaGroup.entrySet()) {
            LocalDate dia = entry.getKey();
            List<Transacao> txList = entry.getValue();

            double diaBruto = txList.stream().mapToDouble(t -> t.getValor() != null ? t.getValor() : 0.0).sum();
            double diaEstornos = txList.stream().mapToDouble(t -> t.getValorEstorno() != null ? t.getValorEstorno() : 0.0).sum();
            double diaPremios = txList.stream().mapToDouble(t -> t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0).sum();
            double diaCash = txList.stream().mapToDouble(t -> t.getValorCash() != null ? t.getValorCash() : 0.0).sum();
            double diaPix = txList.stream().mapToDouble(t -> t.getValorPix() != null ? t.getValorPix() : 0.0).sum();
            double diaCredito = txList.stream().mapToDouble(t -> t.getValorCredito() != null ? t.getValorCredito() : 0.0).sum();
            double diaDebito = txList.stream().mapToDouble(t -> t.getValorDebito() != null ? t.getValorDebito() : 0.0).sum();

            Map<String, Object> itemDia = new LinkedHashMap<>();
            itemDia.put("data", dia.toString());
            itemDia.put("dataFormatada", dia.format(dmyShort));
            itemDia.put("faturamentoBruto", round(diaBruto));
            itemDia.put("faturamentoLiquido", round(diaBruto - diaEstornos));
            itemDia.put("quantidadePremios", round(diaPremios));
            itemDia.put("especie", round(diaCash));
            itemDia.put("pix", round(diaPix));
            itemDia.put("cartao", round(diaCredito + diaDebito));
            listaDias.add(itemDia);
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("titulo", p.titulo());
        resultado.put("periodo", p.periodo());
        resultado.put("dataInicio", p.inicio().toString());
        resultado.put("dataFim", p.fim().toString());

        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("estabelecimentoId", filtroEstabelecimentoId);
        filtros.put("cidade", filtroCidade);
        filtros.put("maquinaId", filtroMaquinaId);
        filtros.put("integracao", filtroIntegracao);
        resultado.put("filtros", filtros);

        resultado.put("resumoGeral", resumoGeral);
        resultado.put("porEstabelecimento", listaEstabelecimentos);
        resultado.put("porMaquina", listaMaquinas);
        resultado.put("porDia", listaDias);

        return resultado;
    }

    private static double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
