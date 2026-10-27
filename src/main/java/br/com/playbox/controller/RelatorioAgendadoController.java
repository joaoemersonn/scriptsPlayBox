package br.com.playbox.controller;

import br.com.playbox.config.OpenApiConfig;
import br.com.playbox.entity.RelatorioAgendado;
import br.com.playbox.service.RelatorioAgendadoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/relatorios-agendados")
@Tag(name = "Relatórios Agendados", description = "Endpoints para gerenciamento personalizado de relatórios agendados dinamicamente")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class RelatorioAgendadoController {

    private final RelatorioAgendadoService service;

    public RelatorioAgendadoController(RelatorioAgendadoService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listar todos os relatórios agendados")
    public ResponseEntity<List<RelatorioAgendado>> listarTodos() {
        return ResponseEntity.ok(service.listarTodos());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar relatório agendado por ID")
    public ResponseEntity<RelatorioAgendado> buscarPorId(@PathVariable Long id) {
        return service.buscarPorId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Criar novo relatório agendado")
    public ResponseEntity<RelatorioAgendado> criar(@RequestBody RelatorioAgendado agendamento) {
        agendamento.setId(null);
        RelatorioAgendado salvo = service.salvar(agendamento);
        return ResponseEntity.status(HttpStatus.CREATED).body(salvo);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualizar relatório agendado existente")
    public ResponseEntity<RelatorioAgendado> atualizar(@PathVariable Long id, @RequestBody RelatorioAgendado agendamento) {
        return service.buscarPorId(id)
                .map(existente -> {
                    agendamento.setId(id);
                    agendamento.setCreatedAt(existente.getCreatedAt());
                    agendamento.setUltimoDisparo(existente.getUltimoDisparo());
                    agendamento.setUltimoStatus(existente.getUltimoStatus());
                    agendamento.setUltimaMensagem(existente.getUltimaMensagem());
                    return ResponseEntity.ok(service.salvar(agendamento));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir relatório agendado")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        service.excluir(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/toggle-ativo")
    @Operation(summary = "Ativar ou desativar relatório agendado")
    public ResponseEntity<RelatorioAgendado> toggleAtivo(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.toggleAtivo(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/disparar-agora")
    @Operation(summary = "Disparar imediatamente o relatório agendado no WhatsApp")
    public ResponseEntity<Map<String, Object>> dispararAgora(@PathVariable Long id) {
        try {
            boolean sucesso = service.executarDisparo(id);
            RelatorioAgendado atualizado = service.buscarPorId(id).orElse(null);
            return ResponseEntity.ok(Map.of(
                    "success", sucesso,
                    "message", sucesso ? "Relatório disparado com sucesso no WhatsApp!" : "Falha ao enviar relatório.",
                    "ultimoStatus", atualizado != null ? String.valueOf(atualizado.getUltimoStatus()) : "",
                    "ultimaMensagem", atualizado != null ? String.valueOf(atualizado.getUltimaMensagem()) : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Erro: " + e.getMessage()));
        }
    }
}
