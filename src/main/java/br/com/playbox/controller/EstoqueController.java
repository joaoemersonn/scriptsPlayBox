package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.MovimentacaoEstoque;
import br.com.playbox.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/estoque")
@Tag(name = "Controle de Estoque", description = "Monitoramento, abastecimento, ajustes manuais e alertas de estoque de pelúcias")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class EstoqueController {

    private final EstoqueService estoqueService;

    public EstoqueController(EstoqueService estoqueService) {
        this.estoqueService = estoqueService;
    }

    public record EstoqueMaquinaDTO(
            Long maquinaId,
            Integer numeroPlanilha,
            String nome,
            String integracao,
            String externalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            String cidade,
            Integer estoqueAtual,
            Integer estoqueMinimoAlerta,
            Integer capacidadeMaxima,
            boolean estoqueBaixo,
            Double percentualOcupacao,
            String ultimaEntrada,
            String ultimaSaida
    ) {
        public static EstoqueMaquinaDTO from(Maquina m) {
            Estabelecimento est = m.getEstabelecimento();
            int atual = m.getEstoqueAtual();
            int cap = m.getCapacidadeMaxima();
            double perc = cap > 0 ? Math.round((atual * 100.0 / cap) * 10.0) / 10.0 : 0.0;

            return new EstoqueMaquinaDTO(
                    m.getId(),
                    m.getNumeroPlanilha(),
                    m.getNomeIntegracao() != null ? m.getNomeIntegracao() : m.getDescricao(),
                    m.getIntegracao(),
                    m.getExternalId(),
                    est != null ? est.getId() : null,
                    est != null ? est.getNome() : null,
                    est != null ? est.getCidade() : null,
                    atual,
                    m.getEstoqueMinimoAlerta(),
                    cap,
                    m.isEstoqueBaixo(),
                    perc,
                    m.getUltimaEntradaEstoque() != null ? m.getUltimaEntradaEstoque().toString() : null,
                    m.getUltimaSaidaEstoque() != null ? m.getUltimaSaidaEstoque().toString() : null
            );
        }
    }

    public record MovimentacaoEstoqueDTO(
            Long id,
            Long maquinaId,
            String maquinaNome,
            String tipo,
            Integer quantidade,
            Integer estoqueAnterior,
            Integer estoqueNovo,
            String origem,
            String observacao,
            String responsavel,
            String dataMovimentacao,
            String externalRef
    ) {
        public static MovimentacaoEstoqueDTO from(MovimentacaoEstoque mov) {
            return new MovimentacaoEstoqueDTO(
                    mov.getId(),
                    mov.getMaquina() != null ? mov.getMaquina().getId() : null,
                    mov.getMaquina() != null ? (mov.getMaquina().getNomeIntegracao() != null ? mov.getMaquina().getNomeIntegracao() : mov.getMaquina().getDescricao()) : null,
                    mov.getTipo(),
                    mov.getQuantidade(),
                    mov.getEstoqueAnterior(),
                    mov.getEstoqueNovo(),
                    mov.getOrigem(),
                    mov.getObservacao(),
                    mov.getResponsavel(),
                    mov.getDataMovimentacao() != null ? mov.getDataMovimentacao().toString() : null,
                    mov.getExternalRef()
            );
        }
    }

    public record AdicionarEstoqueRequest(
            int quantidade,
            String observacao,
            String responsavel
    ) {}

    public record DefinirEstoqueRequest(
            int novoEstoque,
            String observacao,
            String responsavel,
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dataHoraContagem
    ) {}

    public record PreviaRetroativaDTO(
            Long maquinaId,
            String maquinaNome,
            int contagemInformada,
            String dataHoraContagem,
            int saidasPosteriores,
            int entradasPosteriores,
            int estoqueAtualResultante,
            int estoqueAnterior,
            boolean retroativo,
            String mensagem
    ) {}

    public record LimiteAlertaRequest(
            Integer estoqueMinimoAlerta,
            Integer capacidadeMaxima
    ) {}

    @GetMapping
    @Operation(summary = "Consultar estoque das máquinas", description = "Lista máquinas com nível atual de pelúcias, capacidade, alertas de estoque baixo e filtros opcionais.")
    public ResponseEntity<Map<String, Object>> consultarEstoque(
            @Parameter(description = "Filtrar por ID do estabelecimento") @RequestParam(required = false) Long estabelecimentoId,
            @Parameter(description = "Filtrar por cidade do estabelecimento") @RequestParam(required = false) String cidade,
            @Parameter(description = "Filtrar por ID da máquina") @RequestParam(required = false) Long maquinaId,
            @Parameter(description = "Se true, lista apenas máquinas em alerta de estoque baixo") @RequestParam(required = false, defaultValue = "false") Boolean apenasEstoqueBaixo
    ) {
        List<Maquina> maquinas = estoqueService.listarEstoque(estabelecimentoId, cidade, maquinaId, apenasEstoqueBaixo);

        int totalPelucias = 0;
        int maquinasAlerta = 0;
        List<EstoqueMaquinaDTO> dtoList = new ArrayList<>();

        for (Maquina m : maquinas) {
            EstoqueMaquinaDTO dto = EstoqueMaquinaDTO.from(m);
            dtoList.add(dto);
            totalPelucias += dto.estoqueAtual();
            if (dto.estoqueBaixo()) {
                maquinasAlerta++;
            }
        }

        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("totalMaquinas", maquinas.size());
        resumo.put("maquinasEmAlerta", maquinasAlerta);
        resumo.put("totalPeluciasEmEstoque", totalPelucias);
        resumo.put("mediaPeluciasPorMaquina", maquinas.isEmpty() ? 0.0 : Math.round((totalPelucias * 10.0 / maquinas.size())) / 10.0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resumo", resumo);
        response.put("maquinas", dtoList);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{maquinaId}")
    @Operation(summary = "Consultar estoque detalhado de uma máquina")
    public ResponseEntity<?> detalharMaquina(@PathVariable Long maquinaId) {
        return estoqueService.buscarMaquinaPorId(maquinaId)
                .map(m -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("maquina", EstoqueMaquinaDTO.from(m));
                    List<MovimentacaoEstoque> historicoRecente = estoqueService.buscarHistorico(maquinaId, null, null);
                    body.put("movimentacoesRecentes", historicoRecente.stream().limit(10).map(MovimentacaoEstoqueDTO::from).toList());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{maquinaId}/adicionar")
    @Operation(summary = "Adicionar pelúcias (Abastecimento)", description = "Soma uma quantidade de pelúcias ao estoque atual da máquina e registra no histórico.")
    public ResponseEntity<?> adicionarPelucias(
            @PathVariable Long maquinaId,
            @RequestBody AdicionarEstoqueRequest request
    ) {
        try {
            MovimentacaoEstoque mov = estoqueService.adicionarPelucias(
                    maquinaId,
                    request.quantidade(),
                    request.observacao(),
                    request.responsavel()
            );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("mensagem", "Abastecimento realizado com sucesso!");
            response.put("movimentacao", MovimentacaoEstoqueDTO.from(mov));
            response.put("estoqueAtual", mov.getMaquina().getEstoqueAtual());
            response.put("estoqueBaixo", mov.getMaquina().isEstoqueBaixo());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @PutMapping("/{maquinaId}/definir")
    @Operation(summary = "Definir estoque exato (Inventário/Balanço)", description = "Define a quantidade de pelúcias contadas na máquina, com suporte a data/hora retroativa e recálculo automático de saídas posteriores.")
    public ResponseEntity<?> definirEstoque(
            @PathVariable Long maquinaId,
            @RequestBody DefinirEstoqueRequest request
    ) {
        try {
            EstoqueService.ResultadoAjusteEstoque resultado = estoqueService.definirEstoqueComResultado(
                    maquinaId,
                    request.novoEstoque(),
                    request.observacao(),
                    request.responsavel(),
                    request.dataHoraContagem()
            );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("mensagem", resultado.mensagem());
            response.put("movimentacao", MovimentacaoEstoqueDTO.from(resultado.movimentacao()));
            response.put("estoqueAtual", resultado.estoqueAtualResultante());
            response.put("estoqueAnterior", resultado.estoqueAnterior());
            response.put("contagemInformada", resultado.contagemInformada());
            response.put("saidasPosteriores", resultado.saidasPosteriores());
            response.put("entradasPosteriores", resultado.entradasPosteriores());
            response.put("retroativo", resultado.retroativo());
            response.put("estoqueBaixo", resultado.movimentacao().getMaquina().isEstoqueBaixo());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @GetMapping("/{maquinaId}/previa-retroativa")
    @Operation(summary = "Calcular prévia de recálculo retroativo de estoque", description = "Simula o recálculo do estoque atual a partir de uma contagem física informada em data/hora retroativa.")
    public ResponseEntity<?> previaRetroativa(
            @PathVariable Long maquinaId,
            @RequestParam int contagem,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dataHora
    ) {
        try {
            EstoqueService.ResultadoAjusteEstoque prev = estoqueService.calcularPreviaRetroativa(maquinaId, contagem, dataHora);
            String maquinaNome = estoqueService.buscarMaquinaPorId(maquinaId)
                    .map(m -> m.getNomeIntegracao() != null ? m.getNomeIntegracao() : m.getDescricao())
                    .orElse("Máquina " + maquinaId);

            PreviaRetroativaDTO dto = new PreviaRetroativaDTO(
                    maquinaId,
                    maquinaNome,
                    prev.contagemInformada(),
                    dataHora.toString(),
                    prev.saidasPosteriores(),
                    prev.entradasPosteriores(),
                    prev.estoqueAtualResultante(),
                    prev.estoqueAnterior(),
                    prev.retroativo(),
                    prev.mensagem()
            );
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @PutMapping("/{maquinaId}/limite-alerta")
    @Operation(summary = "Configurar limites e alertas de estoque da máquina")
    public ResponseEntity<?> configurarLimites(
            @PathVariable Long maquinaId,
            @RequestBody LimiteAlertaRequest request
    ) {
        try {
            Maquina maq = estoqueService.configurarLimites(
                    maquinaId,
                    request.estoqueMinimoAlerta(),
                    request.capacidadeMaxima()
            );
            return ResponseEntity.ok(EstoqueMaquinaDTO.from(maq));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @GetMapping("/{maquinaId}/historico")
    @Operation(summary = "Histórico de movimentações de estoque da máquina")
    public ResponseEntity<?> buscarHistorico(
            @PathVariable Long maquinaId,
            @Parameter(description = "Data inicial (AAAA-MM-DD)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @Parameter(description = "Data final (AAAA-MM-DD)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim
    ) {
        if (estoqueService.buscarMaquinaPorId(maquinaId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<MovimentacaoEstoque> historico = estoqueService.buscarHistorico(maquinaId, inicio, fim);
        return ResponseEntity.ok(historico.stream().map(MovimentacaoEstoqueDTO::from).toList());
    }

    @PostMapping("/sincronizar-vendpromax")
    @Operation(summary = "Sincronizar estoque nativo do VendProMax", description = "Importa a quantidade de estoque informada diretamente pela controladora/API do VendProMax.")
    public ResponseEntity<?> sincronizarVendProMax() {
        List<Maquina> atualizadas = estoqueService.sincronizarEstoqueVendProMax();
        return ResponseEntity.ok(Map.of(
                "mensagem", "Sincronização com VendProMax concluída",
                "totalAtualizadas", atualizadas.size(),
                "maquinas", atualizadas.stream().map(EstoqueMaquinaDTO::from).toList()
        ));
    }
}
