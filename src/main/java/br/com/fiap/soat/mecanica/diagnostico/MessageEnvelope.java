package br.com.fiap.soat.mecanica.diagnostico;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record MessageEnvelope(UUID messageId, String type, int version, UUID sagaId, UUID osId,
                              String correlationId, UUID causationId, Instant occurredAt, JsonNode payload) {
}
