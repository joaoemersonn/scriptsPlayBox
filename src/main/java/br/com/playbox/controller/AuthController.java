package br.com.playbox.controller;

import br.com.playbox.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação", description = "Endpoints para login e validação de sessão web")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    @Operation(summary = "Realizar login e obter token de sessão")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Usuário e senha são obrigatórios."));
        }

        String token = authService.login(request.username(), request.password());
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "ERROR",
                    "message", "Usuário ou senha incorretos."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "token", token,
                "username", request.username().trim()
        ));
    }

    @GetMapping("/me")
    @Operation(summary = "Verificar dados da sessão atual")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (authService.isValidToken(token)) {
                return ResponseEntity.ok(Map.of(
                        "authenticated", true,
                        "username", authService.getUsername(token)
                ));
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "authenticated", false,
                "message", "Sessão inválida ou expirada."
        ));
    }

    @PostMapping("/logout")
    @Operation(summary = "Encerrar sessão atual")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            authService.logout(token);
        }
        return ResponseEntity.ok(Map.of("message", "Sessão encerrada com sucesso."));
    }
}
