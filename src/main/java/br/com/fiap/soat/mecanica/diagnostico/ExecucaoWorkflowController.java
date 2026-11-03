package br.com.fiap.soat.mecanica.diagnostico;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
class ExecucaoWorkflowController {
    private final ExecucaoWorkflowService workflow;

    ExecucaoWorkflowController(ExecucaoWorkflowService workflow) { this.workflow = workflow; }

    @PostMapping("/execucoes/{osId}/prestacoes")
    Map<String, Object> adicionarPrestacao(@PathVariable UUID osId, @RequestBody PrestacaoRequest request) {
        return workflow.adicionarPrestacao(osId, request.servicoId(), request.precoMdo());
    }

    @PatchMapping("/prestacoes/{id}/inativar")
    Map<String, Object> inativarPrestacao(@PathVariable UUID id) {
        return workflow.inativarPrestacao(id);
    }

    @PostMapping("/execucoes/{osId}/prestacoes/{id}/pecas")
    Map<String, Object> adicionarAlocacao(@PathVariable UUID osId, @PathVariable UUID id,
            @RequestBody AlocacaoRequest request) {
        return workflow.adicionarAlocacao(osId, id, request.pecaId(), request.qtd());
    }

    @PatchMapping("/alocacoes/{id}/inativar")
    Map<String, Object> inativarAlocacao(@PathVariable UUID id) { return workflow.inativarAlocacao(id); }

    @PatchMapping("/execucoes/{osId}/diagnostico/concluir")
    Map<String, Object> concluir(@PathVariable UUID osId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return workflow.concluirDiagnostico(osId, correlationId);
    }

    @PatchMapping("/prestacoes/{id}/finalizar")
    Map<String, Object> finalizar(@PathVariable UUID id,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return workflow.finalizarPrestacao(id, correlationId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException e) { return Map.of("erro", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(IllegalStateException e) { return Map.of("erro", e.getMessage()); }

    record PrestacaoRequest(UUID servicoId, BigDecimal precoMdo) { }
    record AlocacaoRequest(UUID pecaId, int qtd) { }
}
