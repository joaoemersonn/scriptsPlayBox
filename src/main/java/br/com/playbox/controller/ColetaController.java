package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Coleta;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.service.ColetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/coletas")
@Tag(name = "Coleta de Espécie (Cash)", description = "Registro de recolhimento de dinheiro físico nos cofres, cálculo do valor esperado e conferência de caixa posterior")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class ColetaController {

    private final ColetaService coletaService;

    public ColetaController(ColetaService coletaService) {
        this.coletaService = coletaService;
    }

    public record RegistrarColetaRequest(
            Long maquinaId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dataHoraColeta,
            Double valorEsperadoOverride,
            Double valorReal,
            String observacoes,
            String responsavel
    ) {}

    public record ConferirColetaRequest(
            Double valorReal,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dataHoraConferencia,
            String observacoes
    ) {}

    public record ColetaResponseDTO(
            Long id,
            Long maquinaId,
            String maquinaNome,
            String integracao,
            String externalId,
            Long estabelecimentoId,
            String estabelecimentoNome,
            LocalDateTime dataHoraColeta,
            LocalDateTime dataHoraUltimaColeta,
            Double valorEsperado,
            Double valorReal,
            Double diferenca,
            String status,
            LocalDateTime dataHoraConferencia,
            String observacoes,
            String responsavel,
            LocalDateTime createdAt
    ) {
        public static ColetaResponseDTO from(Coleta c) {
            Maquina m = c.getMaquina();
            Estabelecimento e = c.getEstabelecimento();
            return new ColetaResponseDTO(
                    c.getId(),
                    m != null ? m.getId() : null,
                    m != null ? (m.getNomeIntegracao() != null ? m.getNomeIntegracao() : m.getDescricao()) : "Desconhecida",
                    m != null ? m.getIntegracao() : null,
                    m != null ? m.getExternalId() : null,
                    e != null ? e.getId() : null,
                    e != null ? e.getNome() : null,
                    c.getDataHoraColeta(),
                    c.getDataHoraUltimaColeta(),
                    c.getValorEsperado(),
                    c.getValorReal(),
                    c.getDiferenca(),
                    c.getStatus(),
                    c.getDataHoraConferencia(),
                    c.getObservacoes(),
                    c.getResponsavel(),
                    c.getCreatedAt()
            );
        }
    }

    @GetMapping("/previa")
    @Operation(
            summary = "Calcular prévia do valor esperado de uma coleta",
            description = "Calcula quanto deveria ter em espécie na máquina com base na última coleta e nas transações registradas, sem salvar no banco."
    )
    public ResponseEntity<ColetaService.PreviaColetaDTO> calcularPrevia(
            @Parameter(description = "ID da máquina no sistema", required = true)
            @RequestParam Long maquinaId,
            @Parameter(description = "Data e hora pretendida da coleta (padrão: agora)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dataHoraColeta
    ) {
        ColetaService.PreviaColetaDTO previa = coletaService.calcularPrevia(maquinaId, dataHoraColeta);
        return ResponseEntity.ok(previa);
    }

    @PostMapping
    @Operation(
            summary = "Registrar nova retirada/coleta de dinheiro na máquina",
            description = "Registra a sangria/coleta física na máquina. Se valorReal não for informado, a coleta fica como PENDENTE_CONFERENCIA para contagem posterior. Envia notificação no WhatsApp."
    )
    public ResponseEntity<ColetaResponseDTO> registrarColeta(@RequestBody RegistrarColetaRequest request) {
        if (request.maquinaId() == null) {
            return ResponseEntity.badRequest().build();
        }

        Coleta criada = coletaService.registrarColeta(
                request.maquinaId(),
                request.dataHoraColeta(),
                request.valorEsperadoOverride(),
                request.valorReal(),
                request.observacoes(),
                request.responsavel()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ColetaResponseDTO.from(criada));
    }

    @PutMapping("/{id}/conferir")
    @Operation(
            summary = "Conferir e conciliar o valor real de uma coleta pendente",
            description = "Informa o valor físico contado no malote para bater com o valor esperado, calculando a divergência (sobra ou falta) e disparando o recibo no WhatsApp."
    )
    public ResponseEntity<ColetaResponseDTO> conferirColeta(
            @PathVariable Long id,
            @RequestBody ConferirColetaRequest request
    ) {
        if (request.valorReal() == null) {
            return ResponseEntity.badRequest().build();
        }

        Coleta conferida = coletaService.conferirColeta(
                id,
                request.valorReal(),
                request.dataHoraConferencia(),
                request.observacoes()
        );

        return ResponseEntity.ok(ColetaResponseDTO.from(conferida));
    }

    @GetMapping("/pendentes")
    @Operation(
            summary = "Listar coletas que estão aguardando contagem física",
            description = "Retorna todas as coletas cujo status é PENDENTE_CONFERENCIA."
    )
    public ResponseEntity<List<ColetaResponseDTO>> listarPendentes() {
        List<ColetaResponseDTO> lista = coletaService.listarPendentes().stream()
                .map(ColetaResponseDTO::from)
                .toList();
        return ResponseEntity.ok(lista);
    }

    @GetMapping
    @Operation(
            summary = "Listar histórico de coletas com filtros opcionais",
            description = "Permite filtrar coletas por máquina, estabelecimento e status (PENDENTE_CONFERENCIA, CONFERIDO, CONFERIDO_DIVERGENTE)."
    )
    public ResponseEntity<List<ColetaResponseDTO>> listarTodas(
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false) Long estabelecimentoId,
            @RequestParam(required = false) String status
    ) {
        List<ColetaResponseDTO> lista = coletaService.listarTodas(maquinaId, estabelecimentoId, status).stream()
                .map(ColetaResponseDTO::from)
                .toList();
        return ResponseEntity.ok(lista);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obter detalhes de uma coleta por ID")
    public ResponseEntity<ColetaResponseDTO> buscarPorId(@PathVariable Long id) {
        return coletaService.buscarPorId(id)
                .map(c -> ResponseEntity.ok(ColetaResponseDTO.from(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    public record CancelarColetaRequest(
            @Parameter(description = "Motivo do cancelamento (opcional)", example = "Registro incorreto ou coleta não realizada")
            String motivo
    ) {}

    @PutMapping("/{id}/cancelar")
    @Operation(
            summary = "Cancelar um registro de coleta",
            description = "Cancela a coleta física informando motivo opcional. A coleta não será considerada marco zero e não afetará apurações futuras."
    )
    public ResponseEntity<ColetaResponseDTO> cancelarColeta(
            @PathVariable Long id,
            @RequestBody(required = false) CancelarColetaRequest request
    ) {
        String motivo = (request != null) ? request.motivo() : null;
        Coleta cancelada = coletaService.cancelarColeta(id, motivo);
        return ResponseEntity.ok(ColetaResponseDTO.from(cancelada));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir um registro de coleta por ID")
    public ResponseEntity<Void> excluirColeta(@PathVariable Long id) {
        coletaService.excluirColeta(id);
        return ResponseEntity.noContent().build();
    }
}

