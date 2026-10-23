package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.AluguelRepasse;
import br.com.playbox.service.AluguelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alugueis")
@Tag(name = "Aluguéis & Repasses", description = "Controle de aluguéis a vencer, pagamentos e repasses aos estabelecimentos")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class AluguelController {

    private final AluguelService aluguelService;

    public AluguelController(AluguelService aluguelService) {
        this.aluguelService = aluguelService;
    }

    @GetMapping
    @Operation(summary = "Listar todos os aluguéis e repasses com filtros por mês, status, cidade ou loja")
    public List<AluguelRepasse> listar(
            @Parameter(description = "Mês no formato YYYY-MM (ex: 2026-10) ou 'TODOS'", example = "2026-10")
            @RequestParam(required = false) String mesAno,
            @Parameter(description = "Status: 'TODOS', 'EM_ABERTO', 'PAGO', 'VENCIDO'", example = "EM_ABERTO")
            @RequestParam(required = false) String status,
            @Parameter(description = "ID do estabelecimento (opcional)")
            @RequestParam(required = false) Long estabelecimentoId,
            @Parameter(description = "Nome da cidade para filtro (opcional)")
            @RequestParam(required = false) String cidade
    ) {
        return aluguelService.listar(mesAno, status, estabelecimentoId, cidade);
    }

    @GetMapping("/meses")
    @Operation(summary = "Listar todos os meses disponíveis com lançamentos no sistema")
    public List<String> listarMeses() {
        return aluguelService.listarMesesDisponiveis();
    }

    @GetMapping("/resumo")
    @Operation(summary = "Obter resumo estatístico de aluguéis (total, pago, em aberto, vencidos) para um mês ou geral")
    public Map<String, Object> obterResumo(
            @Parameter(description = "Mês no formato YYYY-MM (ex: 2026-10) ou vazio para geral", example = "2026-10")
            @RequestParam(required = false) String mesAno
    ) {
        return aluguelService.obterResumo(mesAno);
    }

    @GetMapping("/estabelecimento/{id}")
    @Operation(summary = "Listar histórico completo de aluguéis e repasses de um estabelecimento")
    public List<AluguelRepasse> listarHistoricoEstabelecimento(@PathVariable Long id) {
        return aluguelService.listarHistoricoEstabelecimento(id);
    }

    @GetMapping("/pendentes")
    @Operation(summary = "Listar aluguéis pendentes de pagamento")
    public List<AluguelRepasse> listarPendentes() {
        return aluguelService.listarPendentes();
    }

    @GetMapping("/vencendo")
    @Operation(summary = "Listar aluguéis a vencer nos próximos dias (ex: 7 dias)")
    public List<AluguelRepasse> listarVencendo(
            @Parameter(description = "Dias à frente para filtrar vencimentos (padrão 7 dias)", example = "7")
            @RequestParam(defaultValue = "7") int dias
    ) {
        return aluguelService.listarVencendoAte(dias);
    }

    @GetMapping("/pagos")
    @Operation(summary = "Listar aluguéis já pagos")
    public List<AluguelRepasse> listarPagos() {
        return aluguelService.listarPagos();
    }

    @PostMapping("/gerar-mes")
    @Operation(summary = "Gerar os lançamentos de aluguel para o mês (ex: 2026-10). Padrão: mês atual")
    public ResponseEntity<List<AluguelRepasse>> gerarMes(
            @Parameter(description = "Mês no formato YYYY-MM (ex: 2026-10)", example = "2026-10")
            @RequestParam(required = false) String mesAno
    ) {
        YearMonth target = (mesAno != null && !mesAno.isBlank()) ? YearMonth.parse(mesAno.trim()) : YearMonth.now();
        List<AluguelRepasse> gerados = aluguelService.gerarCobrancasMes(target);
        return ResponseEntity.ok(gerados);
    }

    @PutMapping("/{id}/pagar")
    @Operation(summary = "Registrar pagamento de um aluguel")
    public ResponseEntity<AluguelRepasse> registrarPagamento(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> dados
    ) {
        LocalDate dataPagamento = LocalDate.now();
        String meio = "PIX";
        String obs = null;

        if (dados != null) {
            if (dados.get("dataPagamento") != null && !dados.get("dataPagamento").isBlank()) {
                dataPagamento = LocalDate.parse(dados.get("dataPagamento").trim());
            }
            if (dados.get("meio") != null) meio = dados.get("meio");
            if (dados.get("obs") != null) obs = dados.get("obs");
        }

        AluguelRepasse atualizado = aluguelService.registrarPagamento(id, dataPagamento, meio, obs);
        return ResponseEntity.ok(atualizado);
    }

    @PutMapping("/{id}/estornar")
    @Operation(summary = "Voltar aluguel para o status pendente (remover pagamento)")
    public ResponseEntity<AluguelRepasse> estornarPagamento(@PathVariable Long id) {
        AluguelRepasse atualizado = aluguelService.estornarPagamento(id);
        return ResponseEntity.ok(atualizado);
    }

    @PutMapping("/{id}/valor")
    @Operation(
            summary = "Definir valor manual do aluguel de um registro",
            description = "Permite fixar ou ajustar o valor do aluguel a ser pago no mês (ex: 'o aluguel desse mês vai ser pago tanto')."
    )
    public ResponseEntity<AluguelRepasse> definirValor(
            @PathVariable Long id,
            @Parameter(description = "Novo valor do aluguel a ser cobrado/pago", example = "248.24")
            @RequestParam Double valor,
            @Parameter(description = "Observação ou motivo do ajuste (opcional)")
            @RequestParam(required = false) String obs
    ) {
        AluguelRepasse atualizado = aluguelService.definirValorAluguel(id, valor, obs);
        return ResponseEntity.ok(atualizado);
    }

    @PutMapping("/definir-valor")
    @Operation(
            summary = "Definir ou ajustar valor de aluguel por Estabelecimento e Mês",
            description = "Permite definir o valor do aluguel para uma loja e mês específicos (ex: estabelecimentoId=3, mesAno=2026-10, valor=248.24)."
    )
    public ResponseEntity<AluguelRepasse> definirValorPorEstabelecimento(
            @Parameter(description = "ID do estabelecimento (ex: 3 para Praça do Sabor)")
            @RequestParam Long estabelecimentoId,
            @Parameter(description = "Mês no formato YYYY-MM (ex: 2026-10)", example = "2026-10")
            @RequestParam String mesAno,
            @Parameter(description = "Valor acordado ou a ser pago neste mês", example = "248.24")
            @RequestParam Double valor,
            @Parameter(description = "Observação opcional")
            @RequestParam(required = false) String obs
    ) {
        YearMonth target = YearMonth.parse(mesAno.trim());
        AluguelRepasse atualizado = aluguelService.definirValorAluguelPorEstabelecimento(estabelecimentoId, target, valor, obs);
        return ResponseEntity.ok(atualizado);
    }

    @PostMapping("/{id}/calcular-dinamico")
    @Operation(
            summary = "Calcular aluguel dinâmico (25% sobre o lucro operacional do mês)",
            description = "Calcula o valor do repasse/aluguel baseado no relatório mensal de operações (Telemetria default R$ 69,90, Manutenção R$ 350,00, Pelúcia R$ 19,90, Repasse 25%)."
    )
    public ResponseEntity<AluguelRepasse> calcularDinamico(
            @PathVariable Long id,
            @Parameter(description = "Custo unitário da pelúcia (default: 19.90)", example = "19.90")
            @RequestParam(required = false) Double valorUnitarioPelucia,
            @Parameter(description = "Taxa de maquininha % (default: 4.99)", example = "4.99")
            @RequestParam(required = false) Double taxaMaquininha,
            @Parameter(description = "Mensalidade telemetria (default: 69.90)", example = "69.90")
            @RequestParam(required = false) Double telemetria,
            @Parameter(description = "Custos de visitas/manutenções (default: 350.00)", example = "350.00")
            @RequestParam(required = false) Double visitas,
            @Parameter(description = "Percentual sobre o lucro (default: 25.0%)", example = "25.0")
            @RequestParam(required = false) Double percentualRepasse
    ) {
        AluguelRepasse atualizado = aluguelService.calcularAluguelDinamico(
                id, valorUnitarioPelucia, taxaMaquininha, telemetria, visitas, percentualRepasse
        );
        return ResponseEntity.ok(atualizado);
    }

    @PostMapping("/calcular-dinamicos")
    @Operation(
            summary = "Recalcular todos os aluguéis dinâmicos de um mês",
            description = "Recalcula o repasse de todas as lojas com aluguel dinâmico (ex: Praça do Sabor) para o mês informado."
    )
    public ResponseEntity<List<AluguelRepasse>> calcularDinamicosMes(
            @Parameter(description = "Mês no formato YYYY-MM (ex: 2026-10). Padrão: mês atual", example = "2026-10")
            @RequestParam(required = false) String mesAno
    ) {
        YearMonth target = (mesAno != null && !mesAno.isBlank()) ? YearMonth.parse(mesAno.trim()) : YearMonth.now();
        List<AluguelRepasse> atualizados = aluguelService.calcularAlugueisDinamicosMes(target);
        return ResponseEntity.ok(atualizados);
    }
}
