package br.com.fiap.soat.mecanica.diagnostico;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AbrirDiagnosticoUseCase {
    private final DiagnosticoRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AbrirDiagnosticoUseCase(DiagnosticoRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean executar(MessageEnvelope command) {
        if (command == null || !"AbrirDiagnostico".equals(command.type()) || command.version() != 1
                || command.messageId() == null || command.sagaId() == null || command.osId() == null
                || command.payload() == null || !command.payload().isObject()) {
            throw new IllegalArgumentException("Comando AbrirDiagnostico inválido");
        }
        UUID payloadOsId = requiredUuid(command.payload().path("osId").asText(null));
        UUID veiculoId = requiredUuid(command.payload().path("veiculoId").asText(null));
        String placa = command.payload().path("placa").asText(null);
        if (!command.osId().equals(payloadOsId) || placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("Payload do diagnóstico inválido");
        }
        Instant now = clock.instant();
        OrdemDiagnostico ordem = new OrdemDiagnostico(command.osId(), command.sagaId(), veiculoId,
                placa.trim().toUpperCase(), now, "EM_DIAGNOSTICO");
        ObjectNode payload = mapper.createObjectNode().put("osId", command.osId().toString());
        MessageEnvelope event = new MessageEnvelope(UUID.randomUUID(), "DiagnosticoAberto", 1,
                command.sagaId(), command.osId(), command.correlationId(), command.messageId(), now, payload);
        return repository.abrirSeAusente(ordem, command.messageId(), event);
    }

    private static UUID requiredUuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("UUID obrigatório inválido", e); }
    }
}
