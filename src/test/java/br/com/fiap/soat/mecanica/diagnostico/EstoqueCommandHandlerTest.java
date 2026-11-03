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
            Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC));
    private final UUID osId = UUID.randomUUID();
    private final UUID pecaId = UUID.randomUUID();

    @Test void indisponibilidadeEmiteRecusaComFaltantes() {
        doThrow(new EstoqueSemDisponibilidadeException("sem estoque", Map.of(pecaId, 1), null))
                .when(estoque).reservar(eq(osId), anyMap());
        handler.executar(reservar());
        ArgumentCaptor<MessageEnvelope> captor = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(outbox).registrar(captor.capture());
        assertEquals("ReservaPecasRecusada", captor.getValue().type());
        assertEquals(pecaId.toString(), captor.getValue().payload().path("faltantes").get(0).path("pecaId").asText());
        assertEquals(1, captor.getValue().payload().path("faltantes").get(0).path("qtd").asInt());
    }

    @Test void conflitoTransitorioNaoEmiteRecusa() {
        doThrow(new EstoqueConflitoTransitorioException("conflito", null))
                .when(estoque).reservar(eq(osId), anyMap());
        assertThrows(EstoqueConflitoTransitorioException.class, () -> handler.executar(reservar()));
        verifyNoInteractions(outbox);
    }

    private MessageEnvelope reservar() {
        return new MessageEnvelope(UUID.randomUUID(), "ReservarPecas", 1, UUID.randomUUID(), osId,
                "trace", null, Instant.now(), mapper.createObjectNode().put("osId", osId.toString())
                .set("pecas", mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("pecaId", pecaId.toString()).put("qtd", 2))));
    }
}
