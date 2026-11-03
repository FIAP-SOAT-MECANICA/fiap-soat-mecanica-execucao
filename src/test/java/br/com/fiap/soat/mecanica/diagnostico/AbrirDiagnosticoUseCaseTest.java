package br.com.fiap.soat.mecanica.diagnostico;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AbrirDiagnosticoUseCaseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID osId = UUID.randomUUID();
    private final UUID sagaId = UUID.randomUUID();
    private final UUID veiculoId = UUID.randomUUID();

    @Test void abreUmaVezEPreparaEventoComCausalidade() {
        InMemoryRepository repo = new InMemoryRepository();
        var useCase = new AbrirDiagnosticoUseCase(repo, mapper,
                Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC));
        MessageEnvelope command = command(osId, veiculoId);

        assertTrue(useCase.executar(command));
        assertFalse(useCase.executar(command));
        assertEquals(1, repo.ordens.size());
        assertEquals(1, repo.events.size());
        assertEquals("EM_DIAGNOSTICO", repo.ordens.get(osId).status());
        assertEquals("ABC1D23", repo.ordens.get(osId).placa());
        MessageEnvelope event = repo.events.values().iterator().next();
        assertEquals("DiagnosticoAberto", event.type());
        assertEquals(command.messageId(), event.causationId());
        assertEquals(command.sagaId(), event.sagaId());
        assertEquals(command.osId(), event.osId());
        assertEquals("trace-1", event.correlationId());
        assertEquals(osId.toString(), event.payload().get("osId").asText());
    }

    @Test void rejeitaOsDiferenteNoPayload() {
        var useCase = new AbrirDiagnosticoUseCase(new InMemoryRepository(), mapper, Clock.systemUTC());
        var command = command(UUID.randomUUID(), veiculoId);
        var invalid = new MessageEnvelope(command.messageId(), command.type(), command.version(), sagaId,
                osId, command.correlationId(), command.causationId(), command.occurredAt(), command.payload());
        assertThrows(IllegalArgumentException.class, () -> useCase.executar(invalid));
    }

    private MessageEnvelope command(UUID order, UUID vehicle) {
        var payload = mapper.createObjectNode().put("osId", order.toString())
                .put("veiculoId", vehicle.toString()).put("placa", "abc1d23");
        return new MessageEnvelope(UUID.randomUUID(), "AbrirDiagnostico", 1, sagaId, order,
                "trace-1", null, Instant.parse("2026-11-03T11:59:59Z"), payload);
    }

    private static class InMemoryRepository implements DiagnosticoRepository {
        final Map<UUID, OrdemDiagnostico> ordens = new HashMap<>();
        final Map<UUID, MessageEnvelope> events = new HashMap<>();

        @Override public boolean abrirSeAusente(OrdemDiagnostico ordem, UUID commandId, MessageEnvelope event) {
            if (ordens.putIfAbsent(ordem.osId(), ordem) != null) return false;
            events.put(event.messageId(), event);
            return true;
        }

        @Override public Optional<OrdemDiagnostico> buscar(UUID osId) {
            return Optional.ofNullable(ordens.get(osId));
        }
    }
}
