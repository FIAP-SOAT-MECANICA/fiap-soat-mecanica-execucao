package br.com.fiap.soat.mecanica.estoque;

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

    private final DynamoDbClient client;
    private final String pecasTable;
    private final String reservasTable;

    public DynamoEstoqueRepository(DynamoDbClient client, String pecasTable, String reservasTable) {
        this.client = Objects.requireNonNull(client);
        this.pecasTable = requireName(pecasTable);
        this.reservasTable = requireName(reservasTable);
    }

    @Override
    public void reservar(ReservaEstoque reserva) {
        List<Reservation> previous = readReservations(reserva.osId());
        if (!previous.isEmpty()) {
            checkIdenticalReservation(reserva, previous);
            return;
        }
        List<TransactWriteItem> actions = new ArrayList<>();
        reserva.pecas().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
            UUID pecaId = entry.getKey();
            int qtd = entry.getValue();
            actions.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(pecasTable).key(pieceKey(pecaId))
                    .updateExpression("ADD qtdDisponivel :menos, qtdReservada :qtd")
                    .conditionExpression("attribute_exists(pecaId) AND qtdDisponivel >= :qtd")
                    .expressionAttributeValues(quantityValues(qtd)).build()).build());
            actions.add(TransactWriteItem.builder().put(Put.builder()
                    .tableName(reservasTable)
                    .item(Map.of("osId", s(reserva.osId()), "pecaId", s(pecaId),
                            "quantidade", n(qtd), "estado", AttributeValue.fromS(RESERVED)))
                    .conditionExpression("attribute_not_exists(osId)").build()).build());
        });
        try {
            transact("reservar", reserva.osId(), actions);
        } catch (TransactionCanceledException failure) {
            List<Reservation> existing = readReservations(reserva.osId());
            if (existing.isEmpty()) {
                boolean transientConflict = failure.cancellationReasons().stream()
                        .anyMatch(reason -> "TransactionConflict".equals(reason.code()));
                if (transientConflict) {
                    throw new EstoqueConflitoTransitorioException(
                            "Conflito transitorio de reserva para OS " + reserva.osId(), failure);
                }
                boolean noStock = failure.cancellationReasons().stream()
                        .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()));
                if (noStock) {
                    throw new EstoqueSemDisponibilidadeException(
                            "Peca ausente ou sem estoque para OS " + reserva.osId(),
                            findMissing(reserva), failure);
                }
                throw new EstoqueConflitoTransitorioException(
                        "Reserva temporariamente indisponivel para OS " + reserva.osId(), failure);
            }
            checkIdenticalReservation(reserva, existing);
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
        changeState(osId, CONFIRMED);
    }

    @Override
    public void liberar(UUID osId) {
        changeState(osId, RELEASED);
    }

    private void changeState(UUID osId, String target) {
        List<Reservation> reservations = readReservations(osId);
        if (reservations.isEmpty()) {
            throw new EstoqueException("Reserva nao encontrada para OS " + osId);
        }
        if (reservations.stream().allMatch(r -> target.equals(r.state()))) {
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
                    .conditionExpression("estado = :reservada")
                    .expressionAttributeValues(values).build()).build());
        }
        try {
            transact(target, osId, actions);
        } catch (TransactionCanceledException failure) {
            List<Reservation> current = readReservations(osId);
            if (current.size() != reservations.size()
                    || current.stream().anyMatch(r -> !target.equals(r.state()))) {
                throw new EstoqueException("Nao foi possivel finalizar reserva da OS " + osId, failure);
            }
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
                reservations.add(new Reservation(UUID.fromString(item.get("pecaId").s()),
                        Integer.parseInt(item.get("quantidade").n()), item.get("estado").s()));
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
                    ? 0 : Integer.parseInt(item.get("qtdDisponivel").n());
            if (available < requested) {
                missing.put(pieceId, requested - available);
            }
        });
        return missing;
    }

    private void transact(String operation, UUID osId, List<TransactWriteItem> actions) {
        String token = UUID.nameUUIDFromBytes((operation + ":" + osId).getBytes(StandardCharsets.UTF_8)).toString();
        client.transactWriteItems(TransactWriteItemsRequest.builder()
                .clientRequestToken(token).transactItems(actions).build());
    }

    private static Map<String, AttributeValue> quantityValues(int qtd) {
        return Map.of(":qtd", n(qtd), ":menos", n(-qtd));
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

    private record Reservation(UUID pecaId, int quantity, String state) { }
}
