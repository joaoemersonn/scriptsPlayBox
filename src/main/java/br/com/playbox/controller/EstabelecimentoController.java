package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.MeioPagamento;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.MeioPagamentoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/estabelecimentos")
@Tag(name = "Estabelecimentos", description = "Gerenciamento de pontos comerciais, meios de pagamento e vinculação de máquinas")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class EstabelecimentoController {

    private final EstabelecimentoRepository repository;
    private final MaquinaRepository maquinaRepository;
    private final MeioPagamentoRepository meioPagamentoRepository;

    public EstabelecimentoController(
            EstabelecimentoRepository repository,
            MaquinaRepository maquinaRepository,
            MeioPagamentoRepository meioPagamentoRepository
    ) {
        this.repository = repository;
        this.maquinaRepository = maquinaRepository;
        this.meioPagamentoRepository = meioPagamentoRepository;
    }

    @GetMapping
    @Operation(summary = "Listar todos os estabelecimentos")
    public List<Estabelecimento> listarTodos(@RequestParam(required = false) Boolean ativo) {
        if (ativo != null) {
            return repository.findAll().stream().filter(e -> e.isAtivo() == ativo).toList();
        }
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
    @Operation(summary = "Atualizar dados do estabelecimento (cidade, responsável, dia de pagamento, valor acordado, etc.)")
    public ResponseEntity<Estabelecimento> atualizar(@PathVariable Long id, @RequestBody Estabelecimento dados) {
        return repository.findById(id).map(existente -> {
            if (dados.getNome() != null) existente.setNome(dados.getNome());
            if (dados.getCidade() != null) existente.setCidade(dados.getCidade());
            if (dados.getResponsavel() != null) existente.setResponsavel(dados.getResponsavel());
            if (dados.getDiaPagamento() != null) existente.setDiaPagamento(dados.getDiaPagamento());
            if (dados.getValorAcordado() != null) existente.setValorAcordado(dados.getValorAcordado());
            if (dados.getDataInicio() != null) existente.setDataInicio(dados.getDataInicio());
            existente.setAtivo(dados.isAtivo());
            return ResponseEntity.ok(repository.save(existente));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/desativar")
    @Operation(summary = "Desativar estabelecimento")
    public ResponseEntity<Estabelecimento> desativar(@PathVariable Long id) {
        return repository.findById(id).map(est -> {
            est.setAtivo(false);
            return ResponseEntity.ok(repository.save(est));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/ativar")
    @Operation(summary = "Reativar estabelecimento")
    public ResponseEntity<Estabelecimento> ativar(@PathVariable Long id) {
        return repository.findById(id).map(est -> {
            est.setAtivo(true);
            return ResponseEntity.ok(repository.save(est));
        }).orElse(ResponseEntity.notFound().build());
    }

    // --- Meios de Pagamento do Estabelecimento ---

    @GetMapping("/{id}/meios-pagamento")
    @Operation(summary = "Listar meios de pagamento do estabelecimento (PIX, Espécie, etc.)")
    public ResponseEntity<List<MeioPagamento>> listarMeiosPagamento(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(meioPagamentoRepository.findByEstabelecimentoId(id));
    }

    @GetMapping("/{id}/chave-pix")
    @Operation(summary = "Obter chave PIX do estabelecimento (retorna a chave principal ou a primeira cadastrada)")
    public ResponseEntity<Map<String, String>> obterChavePix(@PathVariable Long id) {
        Estabelecimento est = repository.findById(id).orElse(null);
        if (est == null) return ResponseEntity.notFound().build();

        List<MeioPagamento> pixList = meioPagamentoRepository.findByEstabelecimentoIdAndTipoIgnoreCase(id, "PIX");
        if (pixList.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "estabelecimento", est.getNome(),
                    "responsavel", est.getResponsavel() != null ? est.getResponsavel() : "",
                    "mensagem", "Nenhum PIX cadastrado para este estabelecimento"
            ));
        }

        MeioPagamento pix = pixList.stream().filter(MeioPagamento::isPrincipal).findFirst().orElse(pixList.get(0));
        return ResponseEntity.ok(Map.of(
                "estabelecimento", est.getNome(),
                "responsavel", est.getResponsavel() != null ? est.getResponsavel() : "",
                "tipo", pix.getTipo(),
                "chavePix", pix.getChaveOuDetalhe() != null ? pix.getChaveOuDetalhe() : "",
                "descricao", pix.getDescricao() != null ? pix.getDescricao() : ""
        ));
    }

    @PostMapping("/{id}/meios-pagamento")
    @Operation(summary = "Adicionar um meio de pagamento ao estabelecimento (PIX, Espécie, etc.)")
    public ResponseEntity<MeioPagamento> adicionarMeioPagamento(@PathVariable Long id, @RequestBody MeioPagamento meio) {
        return repository.findById(id).map(est -> {
            meio.setId(null);
            meio.setEstabelecimento(est);
            MeioPagamento salvo = meioPagamentoRepository.save(meio);
            return ResponseEntity.status(HttpStatus.CREATED).body(salvo);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/meios-pagamento/{meioId}")
    @Operation(summary = "Remover um meio de pagamento do estabelecimento")
    public ResponseEntity<Void> removerMeioPagamento(@PathVariable Long id, @PathVariable Long meioId) {
        if (!meioPagamentoRepository.existsById(meioId)) return ResponseEntity.notFound().build();
        meioPagamentoRepository.deleteById(meioId);
        return ResponseEntity.noContent().build();
    }

    // --- Vinculação de Máquinas ao Estabelecimento ---

    @GetMapping("/{id}/maquinas")
    @Operation(summary = "Listar máquinas alocadas neste estabelecimento")
    public ResponseEntity<List<Maquina>> listarMaquinas(@PathVariable Long id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(maquinaRepository.findByEstabelecimentoId(id));
    }

    @PostMapping("/{id}/vincular-maquina/{maquinaId}")
    @Operation(summary = "Vincular / Alocar uma máquina neste estabelecimento")
    public ResponseEntity<Maquina> vincularMaquina(@PathVariable Long id, @PathVariable Long maquinaId) {
        Estabelecimento est = repository.findById(id).orElse(null);
        if (est == null) return ResponseEntity.notFound().build();

        Maquina maquina = maquinaRepository.findById(maquinaId).orElse(null);
        if (maquina == null) return ResponseEntity.notFound().build();

        maquina.setEstabelecimento(est);
        return ResponseEntity.ok(maquinaRepository.save(maquina));
    }

    @DeleteMapping("/{id}/desvincular-maquina/{maquinaId}")
    @Operation(summary = "Desvincular uma máquina deste estabelecimento")
    public ResponseEntity<Maquina> desvincularMaquina(@PathVariable Long id, @PathVariable Long maquinaId) {
        Maquina maquina = maquinaRepository.findById(maquinaId).orElse(null);
        if (maquina == null) return ResponseEntity.notFound().build();

        if (maquina.getEstabelecimento() != null && maquina.getEstabelecimento().getId().equals(id)) {
            maquina.setEstabelecimento(null);
            return ResponseEntity.ok(maquinaRepository.save(maquina));
        }
        return ResponseEntity.badRequest().build();
    }
}
