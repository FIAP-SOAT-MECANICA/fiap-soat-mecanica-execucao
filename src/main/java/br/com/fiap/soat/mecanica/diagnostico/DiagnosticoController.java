package br.com.fiap.soat.mecanica.diagnostico;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/execucoes")
class DiagnosticoController {
    private final DiagnosticoRepository repository;

    DiagnosticoController(DiagnosticoRepository repository) { this.repository = repository; }

    @GetMapping("/{osId}")
    OrdemDiagnostico buscar(@PathVariable UUID osId) {
        return repository.buscar(osId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
