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

@RestController
@RequestMapping("/api/maquinas")
@Tag(name = "Máquinas", description = "Gerenciamento de máquinas e alocação em estabelecimentos")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class MaquinaController {

    private final MaquinaRepository maquinaRepository;
    private final EstabelecimentoRepository estabelecimentoRepository;
    private final MachineSyncService machineSyncService;

    public MaquinaController(
            MaquinaRepository maquinaRepository,
            EstabelecimentoRepository estabelecimentoRepository,
            MachineSyncService machineSyncService
    ) {
        this.maquinaRepository = maquinaRepository;
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.machineSyncService = machineSyncService;
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

    @PostMapping("/sync")
    @Operation(summary = "Disparar sincronização e descoberta de novas máquinas nas integrações")
    public ResponseEntity<List<Maquina>> sincronizar() {
        List<Maquina> synced = machineSyncService.discoverAndSyncMachines();
        return ResponseEntity.ok(synced);
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
    @Operation(summary = "Mudar ou associar a máquina a um estabelecimento específico")
    public ResponseEntity<Maquina> alocarEstabelecimento(@PathVariable Long id, @PathVariable Long estabelecimentoId) {
        Maquina maquina = maquinaRepository.findById(id).orElse(null);
        if (maquina == null) {
            return ResponseEntity.notFound().build();
        }

        Estabelecimento est = estabelecimentoRepository.findById(estabelecimentoId).orElse(null);
        if (est == null) {
            return ResponseEntity.badRequest().build();
        }

        maquina.setEstabelecimento(est);
        return ResponseEntity.ok(maquinaRepository.save(maquina));
    }

    @DeleteMapping("/{id}/desalocar")
    @Operation(summary = "Desvincular máquina do estabelecimento atual (fica sem estabelecimento)")
    public ResponseEntity<Maquina> desalocarEstabelecimento(@PathVariable Long id) {
        return maquinaRepository.findById(id).map(maquina -> {
            maquina.setEstabelecimento(null);
            return ResponseEntity.ok(maquinaRepository.save(maquina));
        }).orElse(ResponseEntity.notFound().build());
    }
}
