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
}
