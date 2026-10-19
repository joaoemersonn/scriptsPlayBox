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
    private final br.com.playbox.service.RelatorioOperacoesService relatorioOperacoesService;

    public RelatorioController(
            RelatorioService relatorioService,
            br.com.playbox.service.RelatorioOperacoesService relatorioOperacoesService
    ) {
        this.relatorioService = relatorioService;
        this.relatorioOperacoesService = relatorioOperacoesService;
    }

    @GetMapping(value = "/pdf", produces = "application/pdf")
    @Operation(
            summary = "Gerar Relatório Mensal de Operações em PDF (idêntico ao modelo com custos, métricas e histórico)",
            description = "Gera o PDF com os quadros de Métricas de Pelúcia, Entradas (Espécie / Maquininha), Saídas detalhadas, Lucro Total, Repasse (%) e Histórico de Movimentações."
    )
    public ResponseEntity<byte[]> gerarRelatorioPdf(
            @Parameter(description = "ID do estabelecimento (ex: 3 para Praça do Sabor)")
            @RequestParam(required = false) Long estabelecimentoId,

            @Parameter(description = "ID da máquina no banco (opcional)")
            @RequestParam(required = false) Long maquinaId,

            @Parameter(description = "Período predefinido: 'mes', '24h-anterior', '12h-atual', 'hoje' ou 'custom'", example = "mes")
            @RequestParam(required = false) String periodo,

            @Parameter(description = "Data inicial no formato YYYY-MM-DD (ex: 2026-09-01)", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,

            @Parameter(description = "Data final no formato YYYY-MM-DD (ex: 2026-09-30)", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,

            @Parameter(description = "Valor unitário da pelúcia (default: 19.90)", example = "19.90")
            @RequestParam(required = false, defaultValue = "19.90") Double valorUnitarioPelucia,

            @Parameter(description = "Percentual da taxa de maquininha (default: 4.99%)", example = "4.99")
            @RequestParam(required = false, defaultValue = "4.99") Double taxaMaquininhaPercentual,

            @Parameter(description = "Mensalidade do sistema de telemetria (default: 69.90)", example = "69.90")
            @RequestParam(required = false, defaultValue = "69.90") Double mensalidadeTelemetria,

            @Parameter(description = "Visitas técnicas e manutenções (default: 350.00)", example = "350.00")
            @RequestParam(required = false, defaultValue = "350.00") Double visitasManutencao,

            @Parameter(description = "Percentual de repasse/comissão sobre o lucro (default: 25.0%)", example = "25.0")
            @RequestParam(required = false, defaultValue = "25.0") Double percentualRepasse,

            @Parameter(description = "Título customizado da unidade (ex: 'Unidade 02-Praça do Sabor')")
            @RequestParam(required = false) String tituloUnidade,

            @Parameter(description = "Incluir tabela com histórico de movimentações detalhado (default: true)")
            @RequestParam(required = false, defaultValue = "true") boolean incluirHistorico
    ) {
        byte[] pdf = relatorioOperacoesService.gerarPdf(
                estabelecimentoId,
                maquinaId,
                periodo,
                dataInicio,
                dataFim,
                valorUnitarioPelucia,
                taxaMaquininhaPercentual,
                mensalidadeTelemetria,
                visitasManutencao,
                percentualRepasse,
                tituloUnidade,
                incluirHistorico
        );

        String filename = "relatorio-operacoes" + (estabelecimentoId != null ? "-estab-" + estabelecimentoId : "") + ".pdf";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @GetMapping("/operacoes/json")
    @Operation(
            summary = "Consultar dados calculados do Relatório de Operações em formato JSON",
            description = "Retorna os mesmos dados estruturados que alimentam o relatório em PDF (métricas, entradas, saídas, lucro e histórico)."
    )
    public ResponseEntity<br.com.playbox.model.RelatorioOperacoesDTO> obterDadosOperacoesJson(
            @RequestParam(required = false) Long estabelecimentoId,
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false, defaultValue = "19.90") Double valorUnitarioPelucia,
            @RequestParam(required = false, defaultValue = "4.99") Double taxaMaquininhaPercentual,
            @RequestParam(required = false, defaultValue = "69.90") Double mensalidadeTelemetria,
            @RequestParam(required = false, defaultValue = "350.00") Double visitasManutencao,
            @RequestParam(required = false, defaultValue = "25.0") Double percentualRepasse,
            @RequestParam(required = false) String tituloUnidade
    ) {
        br.com.playbox.model.RelatorioOperacoesDTO dto = relatorioOperacoesService.compilarRelatorio(
                estabelecimentoId,
                maquinaId,
                periodo,
                dataInicio,
                dataFim,
                valorUnitarioPelucia,
                taxaMaquininhaPercentual,
                mensalidadeTelemetria,
                visitasManutencao,
                percentualRepasse,
                tituloUnidade
        );
        return ResponseEntity.ok(dto);
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
