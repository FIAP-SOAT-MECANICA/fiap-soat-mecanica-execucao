package br.com.fiap.soat.mecanica.diagnostico;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.fiap.soat.mecanica.estoque.EstoqueConflitoTransitorioException;
import br.com.fiap.soat.mecanica.estoque.EstoqueSemDisponibilidadeException;
import br.com.fiap.soat.mecanica.estoque.EstoqueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EstoqueCommandHandlerTest {
    private final EstoqueService estoque = mock(EstoqueService.class);
    private final EventoOutbox outbox = mock(EventoOutbox.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EstoqueCommandHandler handler = new EstoqueCommandHandler(estoque, outbox, mapper,
            Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC), mock(ExecucaoWorkflowService.class));
    private final UUID osId = UUID.randomUUID();
    private final UUID pecaId = UUID.randomUUID();

    @Test void indisponibilidadeEmiteRecusaComFaltantes() {
        doThrow(new EstoqueSemDisponibilidadeException("sem estoque", Map.of(pecaId, 1), null))
                .when(estoque).reservar(eq(osId), anyMap(), any());
        handler.executar(reservar());
        ArgumentCaptor<MessageEnvelope> captor = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(estoque).registrarResultado(captor.capture());
        assertEquals("ReservaPecasRecusada", captor.getValue().type());
        assertEquals(pecaId.toString(), captor.getValue().payload().path("faltantes").get(0).path("pecaId").asText());
        assertEquals(1, captor.getValue().payload().path("faltantes").get(0).path("qtd").asInt());
    }

    @Test void conflitoTransitorioNaoEmiteRecusa() {
        doThrow(new EstoqueConflitoTransitorioException("conflito", null))
                .when(estoque).reservar(eq(osId), anyMap(), any());
        assertThrows(EstoqueConflitoTransitorioException.class, () -> handler.executar(reservar()));
        verifyNoInteractions(outbox);
    }

    @Test void reservaEntregaEventoParaTransacaoSemGravacaoSeparada() {
        handler.executar(reservar());
        ArgumentCaptor<MessageEnvelope> event = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(estoque).reservar(eq(osId), eq(Map.of(pecaId, 2)), event.capture());
        assertEquals("PecasReservadas", event.getValue().type());
        verifyNoInteractions(outbox);
    }

    @Test void quantidadeFracionariaERejeitada() {
        var command = reservar();
        ((com.fasterxml.jackson.databind.node.ObjectNode) command.payload().path("pecas").get(0))
                .put("qtd", 1.5);
        assertThrows(IllegalArgumentException.class, () -> handler.executar(command));
        verify(estoque).jaProcessado(command.messageId());
        verifyNoMoreInteractions(estoque);
        verifyNoInteractions(outbox);
    }

    @Test void iniciarExecucaoConfirmaReservaETransicionaOrdem() {
        var workflow = mock(ExecucaoWorkflowService.class);
        var integrated = new EstoqueCommandHandler(estoque, outbox, mapper, Clock.systemUTC(), workflow);
        var command = new MessageEnvelope(UUID.randomUUID(), "IniciarExecucao", 1, UUID.randomUUID(),
                osId, "trace", null, Instant.now(), mapper.createObjectNode().put("osId", osId.toString()));
        integrated.executar(command);
        var sequence = inOrder(estoque, workflow);
        sequence.verify(estoque).confirmar(osId);
        sequence.verify(workflow).iniciarExecucao(osId, "trace");
        verifyNoInteractions(outbox);
    }

    private MessageEnvelope reservar() {
        return new MessageEnvelope(UUID.randomUUID(), "ReservarPecas", 1, UUID.randomUUID(), osId,
                "trace", null, Instant.now(), mapper.createObjectNode().put("osId", osId.toString())
                .set("pecas", mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("pecaId", pecaId.toString()).put("qtd", 2))));
    }
}
