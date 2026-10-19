package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.service.RelatorioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/relatorios")
@Tag(name = "Relatórios Financeiros", description = "Consultas consolidadas de faturamento, vendas, estornos e prêmios em formato JSON com múltiplos filtros")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class RelatorioController {

    private final RelatorioService relatorioService;

    public RelatorioController(RelatorioService relatorioService) {
        this.relatorioService = relatorioService;
    }

    @GetMapping
    @Operation(
            summary = "Consultar relatório financeiro consolidado em JSON",
            description = "Retorna o faturamento bruto, líquido, estornos, prêmios e formas de pagamento agrupados por estabelecimento e por máquina com suporte a filtros flexíveis."
    )
    public ResponseEntity<Map<String, Object>> obterRelatorio(
            @Parameter(description = "Período predefinido: '24h-anterior', '12h-atual', 'mes' ou 'hoje'. Ignorado se dataInicio/dataFim forem passados.", example = "mes")
            @RequestParam(required = false) String periodo,

            @Parameter(description = "Data inicial no formato YYYY-MM-DD (ex: 2026-10-01)", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,

            @Parameter(description = "Data final no formato YYYY-MM-DD (ex: 2026-10-19)", example = "2026-10-19")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,

            @Parameter(description = "Filtrar por ID do estabelecimento")
            @RequestParam(required = false) Long estabelecimentoId,

            @Parameter(description = "Filtrar por cidade do estabelecimento (ex: Custódia, Sertânia)")
            @RequestParam(required = false) String cidade,

            @Parameter(description = "Filtrar por ID da máquina no banco")
            @RequestParam(required = false) Long maquinaId,

            @Parameter(description = "Filtrar por integradora (VENDPROMAX, PAYXYZ, ZAPIZI)")
            @RequestParam(required = false) String integracao,

            @Parameter(description = "Forçar sincronização com as APIs antes de gerar o relatório (default: false)")
            @RequestParam(defaultValue = "false") boolean sincronizar
    ) {
        Map<String, Object> relatorio = relatorioService.gerarRelatorioJson(
                periodo,
                dataInicio,
                dataFim,
                estabelecimentoId,
                cidade,
                maquinaId,
                integracao,
                sincronizar
        );
        return ResponseEntity.ok(relatorio);
    }

    @GetMapping("/24h")
    @Operation(summary = "Atalho: Relatório das últimas 24h anteriores (ontem)")
    public ResponseEntity<Map<String, Object>> relatorio24h(
            @RequestParam(required = false) Long estabelecimentoId,
            @RequestParam(required = false) String cidade,
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false) String integracao,
            @RequestParam(defaultValue = "false") boolean sincronizar
    ) {
        return obterRelatorio("24h-anterior", null, null, estabelecimentoId, cidade, maquinaId, integracao, sincronizar);
    }

    @GetMapping("/mes")
    @Operation(summary = "Atalho: Relatório acumulado do mês atual")
    public ResponseEntity<Map<String, Object>> relatorioMes(
            @RequestParam(required = false) Long estabelecimentoId,
            @RequestParam(required = false) String cidade,
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false) String integracao,
            @RequestParam(defaultValue = "false") boolean sincronizar
    ) {
        return obterRelatorio("mes", null, null, estabelecimentoId, cidade, maquinaId, integracao, sincronizar);
    }

    @GetMapping("/12h")
    @Operation(summary = "Atalho: Relatório das últimas 12 horas")
    public ResponseEntity<Map<String, Object>> relatorio12h(
            @RequestParam(required = false) Long estabelecimentoId,
            @RequestParam(required = false) String cidade,
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false) String integracao,
            @RequestParam(defaultValue = "false") boolean sincronizar
    ) {
        return obterRelatorio("12h-atual", null, null, estabelecimentoId, cidade, maquinaId, integracao, sincronizar);
    }
}
