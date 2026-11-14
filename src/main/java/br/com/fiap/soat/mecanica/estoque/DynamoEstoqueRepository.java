package br.com.fiap.soat.mecanica.estoque;

import br.com.fiap.soat.mecanica.diagnostico.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.IdempotentParameterMismatchException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * DynamoDB transaction adapter. Piece items must be created with
 * qtdDisponivel=qtdEstoque and qtdReservada=0. Reservation table has
 * partition key osId and sort key pecaId (both strings).
 */
public final class DynamoEstoqueRepository implements EstoqueRepository {
    private static final String RESERVED = "RESERVADA";
    private static final String CONFIRMED = "CONFIRMADA";
    private static final String RELEASED = "LIBERADA";
    private static final String META = "__CYCLE__";
    private static final String LEGACY = "LEGACY";

    private final DynamoDbClient client;
    private final String pecasTable;
    private final String reservasTable;
    private final String outboxTable;
    private final String idempotenciaTable;
    private final ObjectMapper mapper;

    public DynamoEstoqueRepository(DynamoDbClient client, String pecasTable, String reservasTable) {
        this(client, pecasTable, reservasTable, null, null, null);
    }

    public DynamoEstoqueRepository(DynamoDbClient client, String pecasTable, String reservasTable,
            String idempotenciaTable, String outboxTable, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client);
        this.pecasTable = requireName(pecasTable);
        this.reservasTable = requireName(reservasTable);
        this.outboxTable = outboxTable;
        this.idempotenciaTable = idempotenciaTable;
        this.mapper = mapper;
    }

    @Override
    public void reservar(ReservaEstoque reserva) {
        reservar(reserva, null);
    }

    @Override
    public void reservar(ReservaEstoque reserva, MessageEnvelope event) {
        if (event != null && jaProcessado(event.causationId())) return;
        if (reserva.pecas().size() > (event == null ? 49 : 48))
            throw new IllegalArgumentException("A reserva excede o limite da transacao");
        String cycle = cycle(reserva.cycleId());
        CycleMeta meta = readMeta(reserva.osId());
        List<Reservation> previous = readReservations(reserva.osId());
        List<Reservation> sameCycle = previous.stream().filter(r -> cycle.equals(r.cycle())).toList();
        if ((meta != null && cycle.equals(meta.cycle()) && RESERVED.equals(meta.state()))
                || (meta == null && !sameCycle.isEmpty()
                        && sameCycle.stream().allMatch(r -> RESERVED.equals(r.state())))) {
            checkIdenticalReservation(reserva, sameCycle);
            if (event != null) registrarResultadoNoCiclo(event, cycle, RESERVED);
            return;
        }
        if (LEGACY.equals(cycle) && (meta != null || !previous.isEmpty()))
            throw new EstoqueException("Novo ciclo de reserva exige cycleId");
        if (meta == null && (!previous.isEmpty() || reserva.expectedPreviousCycleId() != null))
            throw new EstoqueException("Ciclo anterior sem marcador; migracao necessaria");
        if (meta != null && !meta.cycle().equals(reserva.expectedPreviousCycleId()))
            throw new EstoqueException("expectedPreviousCycleId nao corresponde ao ultimo ciclo");
        if (meta != null && !RELEASED.equals(meta.state()))
            throw new EstoqueException("Reserva anterior ainda ativa ou confirmada");
        if (previous.stream().anyMatch(r -> RESERVED.equals(r.state())))
            throw new EstoqueException("Reserva anterior ainda ativa");
        List<TransactWriteItem> actions = new ArrayList<>();
        reserva.pecas().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
            UUID pecaId = entry.getKey();
            int qtd = entry.getValue();
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(pecasTable).key(pieceKey(pecaId))
                    .updateExpression("ADD qtdDisponivel :menos, qtdReservada :qtd")
                    .conditionExpression("attribute_exists(pecaId) AND (attribute_not_exists(ativo) OR ativo = :ativo) AND qtdDisponivel >= :qtd")
                    .expressionAttributeValues(reservationValues(qtd)).build()).build());
            actions.add(TransactWriteItem.builder().put(Put.builder()
                    .tableName(reservasTable)
                    .item(Map.of("osId", s(reserva.osId()), "pecaId", s(pecaId),
                            "quantidade", n(qtd), "estado", AttributeValue.fromS(RESERVED),
                            "cycleId", AttributeValue.fromS(cycle)))
                    .conditionExpression("attribute_not_exists(osId) OR (estado = :liberada AND "
                            + "(attribute_not_exists(cycleId) OR cycleId <> :cycle))")
                    .expressionAttributeValues(Map.of(":liberada", AttributeValue.fromS(RELEASED),
                            ":cycle", AttributeValue.fromS(cycle))).build()).build());
        });
        actions.add(metaReservationAction(reserva.osId(), cycle, reserva.expectedPreviousCycleId(), meta));
        if (event != null) {
            actions.add(cycleTombstoneAction(reserva.osId(), cycle));
            actions.add(markerAction(event));
            actions.add(outboxAction(event));
        }
        try {
            transact("reservar", reserva.osId(), actions, event);
        } catch (TransactionCanceledException failure) {
            if (event != null && jaProcessado(event.causationId())) return;
            CycleMeta currentMeta = readMeta(reserva.osId());
            List<Reservation> existing = readReservations(reserva.osId()).stream()
                    .filter(r -> cycle.equals(r.cycle())).toList();
            if (currentMeta != null && cycle.equals(currentMeta.cycle())
                    && RESERVED.equals(currentMeta.state()) && !existing.isEmpty()) {
                checkIdenticalReservation(reserva, existing);
                if (event != null) registrarResultadoNoCiclo(event, cycle, RESERVED);
                return;
            }
            if ((currentMeta != null && !cycle.equals(currentMeta.cycle())
                    && (!RELEASED.equals(currentMeta.state())
                            || !currentMeta.cycle().equals(reserva.expectedPreviousCycleId())))
                    || cycleUsed(reserva.osId(), cycle))
                throw new EstoqueException("Ciclo de reserva ja utilizado ou alterado", failure);
            if (currentMeta == null || !cycle.equals(currentMeta.cycle()) || existing.isEmpty()) {
                boolean transientConflict = failure.cancellationReasons().stream()
                        .anyMatch(reason -> "TransactionConflict".equals(reason.code()));
                if (transientConflict) {
                    throw new EstoqueConflitoTransitorioException(
                            "Conflito transitorio de reserva para OS " + reserva.osId(), failure);
                }
                Map<UUID, Integer> missing = findMissing(reserva);
                if (!missing.isEmpty()) {
                    throw new EstoqueSemDisponibilidadeException(
                            "Peca ausente ou sem estoque para OS " + reserva.osId(),
                            missing, failure);
                }
                throw new EstoqueConflitoTransitorioException(
                        "Reserva temporariamente indisponivel para OS " + reserva.osId(), failure);
            }
            checkIdenticalReservation(reserva, existing);
            if (event != null) registrarResultadoNoCiclo(event, cycle, RESERVED);
        } catch (IdempotentParameterMismatchException failure) {
            throw new EstoqueException("Reserva divergente para OS " + reserva.osId(), failure);
        }
    }

    private static void checkIdenticalReservation(ReservaEstoque requested, List<Reservation> existing) {
        boolean same = existing.size() == requested.pecas().size()
                && existing.stream().allMatch(r -> RESERVED.equals(r.state())
                        && Objects.equals(requested.pecas().get(r.pecaId()), r.quantity()));
        if (!same) {
            throw new EstoqueException("Reserva divergente ou finalizada para OS " + requested.osId());
        }
    }

    @Override
    public void confirmar(UUID osId) {
        changeState(osId, CONFIRMED, null, null);
    }

    @Override
    public void liberar(UUID osId) {
        changeState(osId, RELEASED, null, null);
    }

    @Override
    public void liberar(UUID osId, MessageEnvelope event) {
        liberar(osId, null, event);
    }

    @Override
    public void liberar(UUID osId, UUID cycleId, MessageEnvelope event) {
        if (jaProcessado(event.causationId())) return;
        changeState(osId, RELEASED, cycleId, event);
    }

    private void changeState(UUID osId, String target, UUID cycleId, MessageEnvelope event) {
        String cycle = cycle(cycleId);
        CycleMeta meta = readMeta(osId);
        if (meta != null && !cycle.equals(meta.cycle()))
            throw new EstoqueException("Comando de ciclo de reserva antigo para OS " + osId);
        List<Reservation> reservations = readReservations(osId).stream()
                .filter(r -> cycle.equals(r.cycle())).toList();
        if (reservations.isEmpty()) {
            throw new EstoqueException("Reserva nao encontrada para OS " + osId);
        }
        if (reservations.stream().allMatch(r -> target.equals(r.state()))) {
            if (event != null) registrarResultadoNoCiclo(event, cycle, target);
            return;
        }
        if (reservations.stream().anyMatch(r -> !RESERVED.equals(r.state()))) {
            throw new EstoqueException("Reserva ja finalizada para OS " + osId);
        }
        List<TransactWriteItem> actions = new ArrayList<>();
        for (Reservation reservation : reservations) {
            int qtd = reservation.quantity();
            boolean confirm = CONFIRMED.equals(target);
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(pecasTable).key(pieceKey(reservation.pecaId()))
                    .updateExpression(confirm
                            ? "ADD qtdEstoque :menos, qtdReservada :menos"
                            : "ADD qtdDisponivel :qtd, qtdReservada :menos")
                    .conditionExpression(confirm
                            ? "qtdEstoque >= :qtd AND qtdReservada >= :qtd"
                            : "qtdReservada >= :qtd")
                    .expressionAttributeValues(quantityValues(qtd)).build()).build());
            Map<String, AttributeValue> values = new HashMap<>();
            values.put(":reservada", AttributeValue.fromS(RESERVED));
            values.put(":destino", AttributeValue.fromS(target));
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(reservasTable).key(reservationKey(osId, reservation.pecaId()))
                    .updateExpression("SET estado = :destino")
                    .conditionExpression("estado = :reservada AND "
                            + "(cycleId = :cycle OR (attribute_not_exists(cycleId) AND :cycle = :legacy))")
                    .expressionAttributeValues(Map.of(":reservada", values.get(":reservada"),
                            ":destino", values.get(":destino"), ":cycle", AttributeValue.fromS(cycle),
                            ":legacy", AttributeValue.fromS(LEGACY))).build()).build());
        }
        actions.add(metaStateAction(osId, cycle, meta, target));
        if (event != null) {
            actions.add(markerAction(event));
            actions.add(outboxAction(event));
        }
        try {
            transact(target, osId, actions, event);
        } catch (TransactionCanceledException failure) {
            if (event != null && jaProcessado(event.causationId())) return;
            CycleMeta currentMeta = readMeta(osId);
            List<Reservation> current = readReservations(osId).stream()
                    .filter(r -> cycle.equals(r.cycle())).toList();
            if (current.size() != reservations.size()
                    || current.stream().anyMatch(r -> !target.equals(r.state()))
                    || currentMeta == null || !cycle.equals(currentMeta.cycle())) {
                throw new EstoqueException("Nao foi possivel finalizar reserva da OS " + osId, failure);
            }
            if (event != null) registrarResultadoNoCiclo(event, cycle, target);
        }
    }

    private TransactWriteItem metaStateAction(UUID osId, String cycle, CycleMeta meta, String target) {
        if (meta == null) {
            return TransactWriteItem.builder().put(Put.builder().tableName(reservasTable)
                    .item(Map.of("osId", s(osId), "pecaId", AttributeValue.fromS(META),
                            "cycleId", AttributeValue.fromS(cycle), "estado", AttributeValue.fromS(target)))
                    .conditionExpression("attribute_not_exists(osId)").build()).build();
        }
        return TransactWriteItem.builder().update(Update.builder().tableName(reservasTable)
                .key(metaKey(osId)).updateExpression("SET estado = :target")
                .conditionExpression("cycleId = :cycle AND estado = :reserved")
                .expressionAttributeValues(Map.of(":target", AttributeValue.fromS(target),
                        ":cycle", AttributeValue.fromS(cycle),
                        ":reserved", AttributeValue.fromS(RESERVED))).build()).build();
    }

    private TransactWriteItem outboxAction(MessageEnvelope event) {
        if (outboxTable == null || mapper == null)
            throw new IllegalStateException("Outbox do estoque nao configurada");
        try {
            return TransactWriteItem.builder().put(Put.builder().tableName(outboxTable)
                    .item(Map.of("eventId", s(event.messageId()),
                            "body", AttributeValue.fromS(mapper.writeValueAsString(event)),
                            "createdAt", AttributeValue.fromS(event.occurredAt().toString())))
                    .conditionExpression("attribute_not_exists(eventId)").build()).build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento", e);
        }
    }

    private TransactWriteItem markerAction(MessageEnvelope event) {
        if (idempotenciaTable == null || event.causationId() == null)
            throw new IllegalStateException("Idempotencia do estoque nao configurada");
        return TransactWriteItem.builder().put(Put.builder().tableName(idempotenciaTable)
                .item(Map.of("messageId", s(event.causationId()), "osId", s(event.osId())))
                .conditionExpression("attribute_not_exists(messageId)").build()).build();
    }

    private TransactWriteItem cycleTombstoneAction(UUID osId, String cycle) {
        if (idempotenciaTable == null) throw new IllegalStateException("Idempotencia nao configurada");
        return TransactWriteItem.builder().put(Put.builder().tableName(idempotenciaTable)
                .item(Map.of("messageId", AttributeValue.fromS("cycle:" + osId + ":" + cycle),
                        "osId", s(osId)))
                .conditionExpression("attribute_not_exists(messageId)").build()).build();
    }

    private boolean cycleUsed(UUID osId, String cycle) {
        if (idempotenciaTable == null) return false;
        var item = client.getItem(GetItemRequest.builder().tableName(idempotenciaTable)
                .key(Map.of("messageId", AttributeValue.fromS("cycle:" + osId + ":" + cycle)))
                .consistentRead(true).build()).item();
        return item != null && !item.isEmpty();
    }

    private TransactWriteItem metaReservationAction(UUID osId, String cycle,
            String expectedPreviousCycleId, CycleMeta prior) {
        if (prior == null) {
            return TransactWriteItem.builder().put(Put.builder().tableName(reservasTable)
                    .item(Map.of("osId", s(osId), "pecaId", AttributeValue.fromS(META),
                            "cycleId", AttributeValue.fromS(cycle), "estado", AttributeValue.fromS(RESERVED)))
                    .conditionExpression("attribute_not_exists(osId)").build()).build();
        }
        return TransactWriteItem.builder().update(Update.builder().tableName(reservasTable)
                .key(metaKey(osId)).updateExpression("SET cycleId = :next, estado = :reservada")
                .conditionExpression("cycleId = :prior AND estado = :liberada")
                .expressionAttributeValues(Map.of(":next", AttributeValue.fromS(cycle),
                        ":prior", AttributeValue.fromS(expectedPreviousCycleId),
                        ":reservada", AttributeValue.fromS(RESERVED),
                        ":liberada", AttributeValue.fromS(RELEASED))).build()).build();
    }

    private CycleMeta readMeta(UUID osId) {
        var item = client.getItem(GetItemRequest.builder().tableName(reservasTable)
                .key(metaKey(osId)).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? null
                : new CycleMeta(item.get("cycleId").s(), item.get("estado").s());
    }

    private static Map<String, AttributeValue> metaKey(UUID osId) {
        return Map.of("osId", s(osId), "pecaId", AttributeValue.fromS(META));
    }

    private static String cycle(UUID id) { return id == null ? LEGACY : id.toString(); }

    @Override
    public boolean jaProcessado(UUID messageId) {
        if (idempotenciaTable == null || messageId == null) return false;
        var item = client.getItem(b -> b.tableName(idempotenciaTable)
                .key(Map.of("messageId", s(messageId))).consistentRead(true)).item();
        return item != null && !item.isEmpty();
    }

    private void registrarResultadoNoCiclo(MessageEnvelope event, String cycle, String state) {
        if (jaProcessado(event.causationId())) return;
        CycleMeta meta = readMeta(event.osId());
        if (meta != null && (!cycle.equals(meta.cycle()) || !state.equals(meta.state())))
            throw new EstoqueException("Ciclo de reserva alterado antes do evento");
        var check = ConditionCheck.builder().tableName(reservasTable).key(metaKey(event.osId()));
        if (meta == null) check.conditionExpression("attribute_not_exists(osId)");
        else check.conditionExpression("cycleId = :cycle AND estado = :state")
                .expressionAttributeValues(Map.of(":cycle", AttributeValue.fromS(cycle),
                        ":state", AttributeValue.fromS(state)));
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    TransactWriteItem.builder().conditionCheck(check.build()).build(),
                    markerAction(event), outboxAction(event)).build());
        } catch (TransactionCanceledException e) {
            if (!jaProcessado(event.causationId()))
                throw new EstoqueException("Ciclo de reserva mudou durante o evento", e);
        }
    }

    @Override
    public void registrarRecusa(MessageEnvelope event, UUID cycleId) {
        if (jaProcessado(event.causationId())) return;
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    cycleTombstoneAction(event.osId(), cycle(cycleId)),
                    markerAction(event), outboxAction(event)).build());
        } catch (TransactionCanceledException e) {
            if (!jaProcessado(event.causationId()))
                throw new EstoqueException("Ciclo de reserva ja utilizado", e);
        }
    }

    private List<Reservation> readReservations(UUID osId) {
        var query = QueryRequest.builder().tableName(reservasTable)
                .keyConditionExpression("osId = :osId")
                .expressionAttributeValues(Map.of(":osId", s(osId)))
                .consistentRead(true).build();
        List<Reservation> reservations = new ArrayList<>();
        for (var page : client.queryPaginator(query)) {
            for (var item : page.items()) {
                if (META.equals(item.get("pecaId").s())) continue;
                reservations.add(new Reservation(UUID.fromString(item.get("pecaId").s()),
                        Integer.parseInt(item.get("quantidade").n()), item.get("estado").s(),
                        item.containsKey("cycleId") ? item.get("cycleId").s() : LEGACY));
            }
        }
        return reservations;
    }

    private Map<UUID, Integer> findMissing(ReservaEstoque request) {
        Map<UUID, Integer> missing = new HashMap<>();
        request.pecas().forEach((pieceId, requested) -> {
            var item = client.getItem(b -> b.tableName(pecasTable).key(pieceKey(pieceId))
                    .consistentRead(true)).item();
            int available = item == null || !item.containsKey("qtdDisponivel")
                    || (item.containsKey("ativo") && Boolean.FALSE.equals(item.get("ativo").bool()))
                    ? 0 : Integer.parseInt(item.get("qtdDisponivel").n());
            if (available < requested) {
                missing.put(pieceId, requested - available);
            }
        });
        return missing;
    }

    private void transact(String operation, UUID osId, List<TransactWriteItem> actions, MessageEnvelope event) {
        String token = UUID.nameUUIDFromBytes((operation + ":" + osId + ":"
                + (event == null ? "legacy" : event.causationId())).getBytes(StandardCharsets.UTF_8)).toString();
        client.transactWriteItems(TransactWriteItemsRequest.builder()
                .clientRequestToken(token).transactItems(actions).build());
    }

    private static Map<String, AttributeValue> quantityValues(int qtd) {
        return Map.of(":qtd", n(qtd), ":menos", n(-qtd));
    }

    private static Map<String, AttributeValue> reservationValues(int qtd) {
        return Map.of(":qtd", n(qtd), ":menos", n(-qtd), ":ativo", AttributeValue.fromBool(true));
    }

    private static Map<String, AttributeValue> pieceKey(UUID pecaId) {
        return Map.of("pecaId", s(pecaId));
    }

    private static Map<String, AttributeValue> reservationKey(UUID osId, UUID pecaId) {
        return Map.of("osId", s(osId), "pecaId", s(pecaId));
    }

    private static AttributeValue s(UUID id) {
        return AttributeValue.fromS(id.toString());
    }

    private static AttributeValue n(int value) {
        return AttributeValue.fromN(Integer.toString(value));
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Nome da tabela obrigatorio");
        }
        return value;
    }

    private record Reservation(UUID pecaId, int quantity, String state, String cycle) { }
    private record CycleMeta(String cycle, String state) { }
}
