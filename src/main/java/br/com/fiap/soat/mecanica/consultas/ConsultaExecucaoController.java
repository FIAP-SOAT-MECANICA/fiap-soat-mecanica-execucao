package br.com.fiap.soat.mecanica.consultas;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/execucoes")
public final class ConsultaExecucaoController {
    private final ConsultaExecucaoRepository repository;

    public ConsultaExecucaoController(ConsultaExecucaoRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/fila")
    public FilaPage fila(@RequestParam(defaultValue = "20") int limite,
            @RequestParam(required = false) String cursor) {
        if (limite < 1 || limite > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limite deve estar entre 1 e 100");
        }
        try {
            return repository.listarFila(limite, cursor);
        } catch (IllegalArgumentException invalidCursor) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor invalido", invalidCursor);
        }
    }

    @GetMapping("/tempo-medio")
    public List<TempoMedioServico> tempoMedio() {
        return repository.temposMedios();
    }
}
