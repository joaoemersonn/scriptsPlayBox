package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.repository.EstabelecimentoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/estabelecimentos")
@Tag(name = "Estabelecimentos", description = "Gerenciamento de pontos comerciais / locais onde as máquinas são alocadas")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class EstabelecimentoController {

    private final EstabelecimentoRepository repository;

    public EstabelecimentoController(EstabelecimentoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Listar todos os estabelecimentos")
    public List<Estabelecimento> listarTodos() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar estabelecimento por ID")
    public ResponseEntity<Estabelecimento> buscarPorId(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Cadastrar novo estabelecimento")
    public ResponseEntity<Estabelecimento> cadastrar(@RequestBody Estabelecimento estabelecimento) {
        estabelecimento.setId(null);
        Estabelecimento salvo = repository.save(estabelecimento);
        return ResponseEntity.status(HttpStatus.CREATED).body(salvo);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualizar dados do estabelecimento (cidade, responsável, pix, dia de pagamento, etc.)")
    public ResponseEntity<Estabelecimento> atualizar(@PathVariable Long id, @RequestBody Estabelecimento dados) {
        return repository.findById(id).map(existente -> {
            if (dados.getNome() != null) existente.setNome(dados.getNome());
            if (dados.getCidade() != null) existente.setCidade(dados.getCidade());
            if (dados.getResponsavel() != null) existente.setResponsavel(dados.getResponsavel());
            if (dados.getChavePix() != null) existente.setChavePix(dados.getChavePix());
            if (dados.getDiaPagamento() != null) existente.setDiaPagamento(dados.getDiaPagamento());
            if (dados.getValorAcordado() != null) existente.setValorAcordado(dados.getValorAcordado());
            if (dados.getDataInicio() != null) existente.setDataInicio(dados.getDataInicio());
            existente.setAtivo(dados.isAtivo());
            return ResponseEntity.ok(repository.save(existente));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativar ou remover estabelecimento")
    public ResponseEntity<Void> remover(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
