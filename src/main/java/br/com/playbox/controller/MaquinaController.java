package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.service.MachineSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/maquinas")
@Tag(name = "Máquinas", description = "Gerenciamento de máquinas e alocação em estabelecimentos")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class MaquinaController {

    private final MaquinaRepository maquinaRepository;
    private final EstabelecimentoRepository estabelecimentoRepository;
    private final MachineSyncService machineSyncService;
    private final br.com.playbox.service.HistoricoVinculoService historicoVinculoService;

    public MaquinaController(
            MaquinaRepository maquinaRepository,
            EstabelecimentoRepository estabelecimentoRepository,
            MachineSyncService machineSyncService,
            br.com.playbox.service.HistoricoVinculoService historicoVinculoService
    ) {
        this.maquinaRepository = maquinaRepository;
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.machineSyncService = machineSyncService;
        this.historicoVinculoService = historicoVinculoService;
    }

    @GetMapping
    @Operation(summary = "Listar todas as máquinas")
    public List<Maquina> listarTodas() {
        return maquinaRepository.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar máquina por ID")
    public ResponseEntity<Maquina> buscarPorId(@PathVariable Long id) {
        return maquinaRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/fetch-info")
    @Operation(summary = "Puxar nome/dados da máquina na integradora pelo ID externo")
    public ResponseEntity<Map<String, String>> fetchExternalInfo(
            @RequestParam String integracao,
            @RequestParam String externalId
    ) {
        String nome = machineSyncService.fetchExternalMachineName(integracao, externalId);
        if (nome != null) {
            return ResponseEntity.ok(Map.of("nome", nome, "integracao", integracao, "externalId", externalId));
        }
        return ResponseEntity.ok(Map.of("nome", "", "integracao", integracao, "externalId", externalId));
    }

    @PostMapping
    @Operation(summary = "Cadastrar máquina manualmente")
    public ResponseEntity<Maquina> cadastrar(@RequestBody Maquina maquina) {
        maquina.setId(null);
        Maquina salva = maquinaRepository.save(maquina);
        return ResponseEntity.status(HttpStatus.CREATED).body(salva);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualizar detalhes da máquina (número da planilha, descrição, ativo)")
    public ResponseEntity<Maquina> atualizar(@PathVariable Long id, @RequestBody Maquina dados) {
        return maquinaRepository.findById(id).map(existente -> {
            if (dados.getNumeroPlanilha() != null) existente.setNumeroPlanilha(dados.getNumeroPlanilha());
            if (dados.getDescricao() != null) existente.setDescricao(dados.getDescricao());
            if (dados.getNomeIntegracao() != null) existente.setNomeIntegracao(dados.getNomeIntegracao());
            if (dados.getExternalId() != null) existente.setExternalId(dados.getExternalId());
            if (dados.getIntegracao() != null) existente.setIntegracao(dados.getIntegracao());
            existente.setAtivo(dados.isAtivo());
            return ResponseEntity.ok(maquinaRepository.save(existente));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/desativar")
    @Operation(summary = "Desativar máquina")
    public ResponseEntity<Maquina> desativar(@PathVariable Long id) {
        return maquinaRepository.findById(id).map(maq -> {
            maq.setAtivo(false);
            return ResponseEntity.ok(maquinaRepository.save(maq));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/ativar")
    @Operation(summary = "Reativar máquina")
    public ResponseEntity<Maquina> ativar(@PathVariable Long id) {
        return maquinaRepository.findById(id).map(maq -> {
            maq.setAtivo(true);
            return ResponseEntity.ok(maquinaRepository.save(maq));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/alocar/{estabelecimentoId}")
    @Operation(summary = "Mudar ou associar a máquina a um estabelecimento específico com data/hora de vigência")
    public ResponseEntity<Maquina> alocarEstabelecimento(
            @PathVariable Long id,
            @PathVariable Long estabelecimentoId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dataHora,
            @RequestParam(required = false) String motivo
    ) {
        if (!maquinaRepository.existsById(id) || !estabelecimentoRepository.existsById(estabelecimentoId)) {
            return ResponseEntity.notFound().build();
        }

        historicoVinculoService.alocarMaquina(id, estabelecimentoId, dataHora, motivo);
        return ResponseEntity.ok(maquinaRepository.findById(id).orElse(null));
    }

    @DeleteMapping("/{id}/desalocar")
    @Operation(summary = "Desvincular máquina do estabelecimento atual com data/hora de encerramento")
    public ResponseEntity<Maquina> desalocarEstabelecimento(
            @PathVariable Long id,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime dataHora,
            @RequestParam(required = false) String motivo
    ) {
        if (!maquinaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        historicoVinculoService.desalocarMaquina(id, dataHora, motivo);
        return ResponseEntity.ok(maquinaRepository.findById(id).orElse(null));
    }

    @GetMapping("/{id}/historico-vinculos")
    @Operation(summary = "Listar histórico temporal de alocações desta máquina")
    public ResponseEntity<List<br.com.playbox.dto.HistoricoVinculoDTO>> getHistorico(@PathVariable Long id) {
        if (!maquinaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(historicoVinculoService.getHistoricoByMaquina(id));
    }

    @PostMapping("/{id}/reassociar-transacoes")
    @Operation(summary = "Reassociar transações órfãs desta máquina ao estabelecimento baseado no histórico temporal")
    public ResponseEntity<Map<String, Object>> reassociarTransacoes(@PathVariable Long id) {
        if (!maquinaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        int total = historicoVinculoService.reassociarTransacoesOrfas(id);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "reassociadas", total));
    }
}
