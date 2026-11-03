package br.com.fiap.soat.mecanica.diagnostico;

import br.com.fiap.soat.mecanica.estoque.EstoqueService;
import br.com.fiap.soat.mecanica.estoque.EstoqueSemDisponibilidadeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class EstoqueCommandHandler {
    private final EstoqueService estoque;
    private final EventoOutbox outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    EstoqueCommandHandler(EstoqueService estoque, EventoOutbox outbox, ObjectMapper mapper, Clock clock) {
        this.estoque = estoque;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    boolean supports(String type) {
        return "ReservarPecas".equals(type) || "LiberarReservaPecas".equals(type)
                || "IniciarExecucao".equals(type);
    }

    void executar(MessageEnvelope command) {
        if (command.version() != 1 || command.messageId() == null || command.sagaId() == null
                || command.osId() == null || command.payload() == null || !command.payload().isObject()
                || !command.osId().toString().equals(command.payload().path("osId").asText())) {
            throw new IllegalArgumentException("Comando de estoque inválido");
        }
        String eventType;
        Map<UUID, Integer> faltantes = Map.of();
        switch (command.type()) {
            case "ReservarPecas" -> {
                Map<UUID, Integer> pecas = new HashMap<>();
                var itens = command.payload().path("pecas");
                if (!itens.isArray() || itens.isEmpty()) throw new IllegalArgumentException("Peças ausentes");
                for (var item : itens) {
                    UUID pecaId = UUID.fromString(item.path("pecaId").asText());
                    int quantidade = item.path("qtd").asInt(0);
                    if (quantidade <= 0 || pecas.putIfAbsent(pecaId, quantidade) != null)
                        throw new IllegalArgumentException("Peça ou quantidade inválida");
                }
                try {
                    estoque.reservar(command.osId(), pecas);
                    eventType = "PecasReservadas";
                } catch (EstoqueSemDisponibilidadeException e) {
                    eventType = "ReservaPecasRecusada";
                    faltantes = e.faltantes();
                }
            }
            case "LiberarReservaPecas" -> {
                estoque.liberar(command.osId());
                eventType = "ReservaPecasLiberada";
            }
            case "IniciarExecucao" -> {
                estoque.confirmar(command.osId());
                eventType = "ExecucaoIniciada";
            }
            default -> throw new IllegalArgumentException("Comando de estoque desconhecido");
        }
        ObjectNode payload = mapper.createObjectNode().put("osId", command.osId().toString());
        if ("ReservaPecasRecusada".equals(eventType)) {
            var array = payload.putArray("faltantes");
            faltantes.forEach((pecaId, quantidade) -> array.addObject()
                    .put("pecaId", pecaId.toString()).put("qtd", quantidade));
        }
        UUID eventId = UUID.nameUUIDFromBytes((command.messageId() + ":" + eventType)
                .getBytes(StandardCharsets.UTF_8));
        outbox.registrar(new MessageEnvelope(eventId, eventType, 1, command.sagaId(), command.osId(),
                command.correlationId(), command.messageId(), clock.instant(), payload));
    }
}
