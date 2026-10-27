package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.RelatorioService;
import br.com.playbox.service.TransactionImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transacoes")
@Tag(name = "Transações", description = "Consulta e sincronização das transações e vendas registradas localmente")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class TransacaoController {

    private final TransacaoRepository transacaoRepository;
    private final TransactionImportService transactionImportService;
    private final RelatorioService relatorioService;

    public TransacaoController(
            TransacaoRepository transacaoRepository,
            TransactionImportService transactionImportService,
            RelatorioService relatorioService
    ) {
        this.transacaoRepository = transacaoRepository;
        this.transactionImportService = transactionImportService;
        this.relatorioService = relatorioService;
    }

    public record TransacaoDTO(
            Long id,
            String externalTxId,
            String integracao,
            Long maquinaId,
            String maquinaNome,
            String maquinaExternalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            String cidade,
            String dataHora,
            String tipoPagamento,
            Double valorBruto,
            Double valorCash,
            Double valorPix,
            Double valorCredito,
            Double valorDebito,
            Double valorEstorno,
            Double valorLiquido,
            Double quantidadePremios,
            Boolean teste,
            Double valorTeste,
            String motivoTeste,
            Double valorEfetivo,
            Double valorCashEfetivo
    ) {}

    public record AlterarTesteDTO(
            Boolean teste,
            Double valorTeste,
            String motivo
    ) {}

    public record MarcarTestePeriodoDTO(
            Long maquinaId,
            Long estabelecimentoId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            Boolean teste,
            Double valorTeste,
            String motivo
    ) {}

    @GetMapping
    @Operation(summary = "Listar transações filtradas em JSON")
    public ResponseEntity<List<TransacaoDTO>> listarTransacoes(
            @Parameter(description = "Período: '24h-anterior', '12h-atual', 'mes', 'hoje'. Ignorado se dataInicio/dataFim forem passados.", example = "mes")
            @RequestParam(required = false) String periodo,

            @Parameter(description = "Data inicial no formato YYYY-MM-DD", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,

            @Parameter(description = "Data final no formato YYYY-MM-DD", example = "2026-10-19")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,

            @Parameter(description = "Filtrar por ID do estabelecimento")
            @RequestParam(required = false) Long estabelecimentoId,

            @Parameter(description = "Filtrar por cidade do estabelecimento")
            @RequestParam(required = false) String cidade,

            @Parameter(description = "Filtrar por ID da máquina")
            @RequestParam(required = false) Long maquinaId,

            @Parameter(description = "Filtrar por integradora (VENDPROMAX, PAYXYZ, ZAPIZI)")
            @RequestParam(required = false) String integracao,

            @Parameter(description = "Filtrar por status de teste (null: todas, true: apenas testes, false: apenas válidas)")
            @RequestParam(required = false) Boolean teste,

            @Parameter(description = "Sincronizar dados das APIs antes de consultar (default: false)")
            @RequestParam(defaultValue = "false") boolean sincronizar
    ) {
        RelatorioService.PeriodoCalculado p = relatorioService.calcularPeriodo(periodo, dataInicio, dataFim);

        if (sincronizar) {
            transactionImportService.importTransactionsRange(p.inicio().toLocalDate(), p.fim().toLocalDate());
        } else {
            transactionImportService.ensureTransactionsImported(p.inicio().toLocalDate(), p.fim().toLocalDate());
        }

        List<Transacao> lista = transacaoRepository.findFiltered(
                p.inicio(),
                p.fim(),
                estabelecimentoId,
                cidade != null && !cidade.isBlank() ? cidade.trim() : null,
                maquinaId,
                integracao != null && !integracao.isBlank() ? integracao.trim() : null,
                teste
        );

        List<TransacaoDTO> dtos = lista.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(dtos);
    }

    @PutMapping("/{id}/teste")
    @Operation(summary = "Marcar ou desmarcar transação individual como teste e definir valor de teste")
    public ResponseEntity<TransacaoDTO> alterarTeste(
            @PathVariable Long id,
            @RequestBody AlterarTesteDTO body
    ) {
        Transacao t = transacaoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transação não encontrada para o ID: " + id));

        boolean isTeste = body != null && Boolean.TRUE.equals(body.teste());
        t.setTeste(isTeste);
        if (isTeste) {
            Double vt = body.valorTeste();
            t.setValorTeste(vt != null && vt > 0 ? vt : 0.0);
            t.setMotivoTeste(body.motivo() != null && !body.motivo().isBlank() ? body.motivo().trim() : "Teste operacional");
        } else {
            t.setValorTeste(0.0);
            t.setMotivoTeste(null);
        }

        Transacao salvo = transacaoRepository.save(t);
        return ResponseEntity.ok(toDTO(salvo));
    }

    @PostMapping("/marcar-teste-periodo")
    @Operation(summary = "Marcar ou desmarcar em lote transações de um período/máquina como teste")
    public ResponseEntity<Map<String, Object>> marcarTestePeriodo(
            @RequestBody MarcarTestePeriodoDTO body
    ) {
        if (body.dataInicio() == null || body.dataFim() == null) {
            throw new IllegalArgumentException("As datas inicial e final são obrigatórias.");
        }

        LocalDate start = body.dataInicio();
        LocalDate end = body.dataFim();
        if (start.isAfter(end)) {
            LocalDate temp = start;
            start = end;
            end = temp;
        }

        LocalDateTime inicio = start.atStartOfDay();
        LocalDateTime fim = end.atTime(java.time.LocalTime.MAX);

        List<Transacao> lista = transacaoRepository.findByPeriodAndFilters(inicio, fim, body.maquinaId(), body.estabelecimentoId());
        boolean isTeste = body.teste() == null || Boolean.TRUE.equals(body.teste());
        Double valorTeste = body.valorTeste() != null && body.valorTeste() > 0 ? body.valorTeste() : 0.0;
        String motivo = body.motivo() != null && !body.motivo().isBlank() ? body.motivo().trim() : (isTeste ? "Período de Testes" : null);

        for (Transacao t : lista) {
            t.setTeste(isTeste);
            if (isTeste) {
                t.setValorTeste(valorTeste);
                t.setMotivoTeste(motivo);
            } else {
                t.setValorTeste(0.0);
                t.setMotivoTeste(null);
            }
        }

        transacaoRepository.saveAll(lista);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "transacoesAfetadas", lista.size(),
                "acao", isTeste ? "MARCADO_TESTE" : "REVERTIDO_VALIDO",
                "mensagem", String.format("%d transação(ões) %s com sucesso para o período de %s a %s.",
                        lista.size(), isTeste ? "desconsideradas como teste" : "revalidadas", start, end)
        ));
    }

    private TransacaoDTO toDTO(Transacao t) {
        Maquina m = t.getMaquina();
        Estabelecimento e = t.getEstabelecimento() != null ? t.getEstabelecimento() : (m != null ? m.getEstabelecimento() : null);

        double bruto = t.getValor() != null ? t.getValor() : 0.0;
        double estorno = t.getValorEstorno() != null ? t.getValorEstorno() : 0.0;
        double liquido = Math.round((bruto - estorno) * 100.0) / 100.0;

        return new TransacaoDTO(
                t.getId(),
                t.getExternalTxId(),
                t.getIntegracao(),
                m != null ? m.getId() : null,
                m != null && m.getNomeIntegracao() != null ? m.getNomeIntegracao() : t.getExternalTxId(),
                m != null ? m.getExternalId() : "",
                e != null ? e.getId() : null,
                e != null ? e.getNome() : "Sem Estabelecimento",
                e != null ? e.getCidade() : "",
                t.getTimestamp() != null ? t.getTimestamp().toString() : "",
                t.getTipoPagamento(),
                bruto,
                t.getValorCash() != null ? t.getValorCash() : 0.0,
                t.getValorPix() != null ? t.getValorPix() : 0.0,
                t.getValorCredito() != null ? t.getValorCredito() : 0.0,
                t.getValorDebito() != null ? t.getValorDebito() : 0.0,
                estorno,
                liquido,
                t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0,
                t.getTeste(),
                t.getValorTeste(),
                t.getMotivoTeste(),
                Math.round(t.getValorEfetivo() * 100.0) / 100.0,
                Math.round(t.getValorCashEfetivo() * 100.0) / 100.0
        );
    }

    @PostMapping("/sincronizar")
    @Operation(summary = "Importar/sincronizar transações das integradoras para o banco local")
    public ResponseEntity<Map<String, Object>> sincronizar(
            @Parameter(description = "Data inicial no formato YYYY-MM-DD. Padrão: 1º dia do mês atual", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,

            @Parameter(description = "Data final no formato YYYY-MM-DD. Padrão: ontem", example = "2026-10-18")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim
    ) {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        LocalDate hoje = LocalDate.now(zoneId);
        LocalDate ontem = hoje.minusDays(1);

        LocalDate start = dataInicio != null ? dataInicio : hoje.withDayOfMonth(1);
        LocalDate end = dataFim != null ? dataFim : ontem;

        if (start.isAfter(end)) {
            LocalDate temp = start;
            start = end;
            end = temp;
        }

        int count = transactionImportService.importTransactionsRange(start, end);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "dataInicio", start.toString(),
                "dataFim", end.toString(),
                "registrosSincronizados", count
        ));
    }
}
