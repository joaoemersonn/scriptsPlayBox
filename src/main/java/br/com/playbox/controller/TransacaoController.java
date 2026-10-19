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
            Double quantidadePremios
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
                integracao != null && !integracao.isBlank() ? integracao.trim() : null
        );

        List<TransacaoDTO> dtos = lista.stream().map(t -> {
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
                    t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0
            );
        }).toList();

        return ResponseEntity.ok(dtos);
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
