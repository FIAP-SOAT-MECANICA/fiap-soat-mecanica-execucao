package br.com.fiap.soat.mecanica.estoque;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pecas")
class PecaController {
    private final PecaRepository repository;

    PecaController(PecaRepository repository) { this.repository = repository; }

    @PostMapping
    ResponseEntity<Peca> criar(@RequestBody PecaRequest request) {
        Peca peca = repository.criar(request.toPeca(UUID.randomUUID()));
        return ResponseEntity.created(URI.create("/pecas/" + peca.pecaId())).body(peca);
    }

    @GetMapping("/{id}")
    Peca buscar(@PathVariable UUID id) {
        return repository.buscar(id).orElseThrow(() -> new PecaNaoEncontradaException(id));
    }

    @GetMapping
    List<Peca> listar() { return repository.listar(); }

    @PutMapping("/{id}")
    Peca atualizar(@PathVariable UUID id, @RequestBody PecaRequest request) {
        return repository.atualizar(request.toPeca(id));
    }

    @ExceptionHandler(PecaNaoEncontradaException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(PecaNaoEncontradaException e) { return e.getMessage(); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException e) { return e.getMessage(); }

    @ExceptionHandler(EstoqueException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String conflict(EstoqueException e) { return e.getMessage(); }

    record PecaRequest(String nome, String descricao, BigDecimal valorUnitario, Integer qtdEstoque) {
        Peca toPeca(UUID id) {
            if (nome == null || nome.isBlank() || descricao == null || descricao.isBlank()
                    || valorUnitario == null || valorUnitario.signum() < 0
                    || qtdEstoque == null || qtdEstoque < 0)
                throw new IllegalArgumentException("Nome, descrição, valor e quantidade válidos são obrigatórios");
            return new Peca(id, nome.strip(), descricao.strip(), valorUnitario,
                    qtdEstoque, qtdEstoque, 0);
        }
    }

    private static class PecaNaoEncontradaException extends RuntimeException {
        PecaNaoEncontradaException(UUID id) { super("Peça não encontrada: " + id); }
    }
}
