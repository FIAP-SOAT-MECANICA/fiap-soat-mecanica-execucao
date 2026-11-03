package br.com.fiap.soat.mecanica.diagnostico;

import br.com.fiap.soat.mecanica.consultas.FilaKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

@Repository
class DynamoDiagnosticoRepository implements DiagnosticoRepository {
    private final DynamoDbClient dynamo;
    private final ObjectMapper mapper;
    private final String ordensTable;
    private final String idempotenciaTable;
    private final String outboxTable;

    DynamoDiagnosticoRepository(DynamoDbClient dynamo, ObjectMapper mapper,
            @Value("${execucao.diagnostico.ordens-table}") String ordensTable,
            @Value("${execucao.diagnostico.idempotencia-table}") String idempotenciaTable,
            @Value("${execucao.diagnostico.outbox-table}") String outboxTable) {
        this.dynamo = dynamo;
        this.mapper = mapper;
        this.ordensTable = ordensTable;
        this.idempotenciaTable = idempotenciaTable;
        this.outboxTable = outboxTable;
    }

    @Override public boolean abrirSeAusente(OrdemDiagnostico ordem, UUID commandId, MessageEnvelope event) {
        Map<String, AttributeValue> order = new HashMap<>();
        order.put("osId", s(ordem.osId().toString()));
        order.put("sagaId", s(ordem.sagaId().toString()));
        order.put("veiculoId", s(ordem.veiculoId().toString()));
        order.put("placa", s(ordem.placa()));
        order.put("abertaEm", s(ordem.abertaEm().toString()));
        order.put("status", s(ordem.status()));
        order.put("prioridade", AttributeValue.fromN("0"));
        order.put("version", AttributeValue.fromN("0"));
        order.put("prestacoes", AttributeValue.builder().l(java.util.List.of()).build());
        order.putAll(FilaKey.attributes(ordem.status(), ordem.abertaEm().toString(), ordem.osId()));
        Map<String, AttributeValue> idempotencia = Map.of(
                "messageId", s(commandId.toString()), "osId", s(ordem.osId().toString()),
                "expiresAt", AttributeValue.builder().n(Long.toString(ordem.abertaEm().plusSeconds(604800).getEpochSecond())).build());
        String json;
        try { json = mapper.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Falha ao serializar evento", e); }
        Map<String, AttributeValue> outbox = Map.of(
                "eventId", s(event.messageId().toString()), "body", s(json),
                "createdAt", s(event.occurredAt().toString()));
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(ordensTable).item(order)
                            .conditionExpression("attribute_not_exists(osId)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(idempotenciaTable).item(idempotencia)
                            .conditionExpression("attribute_not_exists(messageId)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(outboxTable).item(outbox)
                            .conditionExpression("attribute_not_exists(eventId)").build()).build()).build());
            return true;
        } catch (TransactionCanceledException e) {
            OrdemDiagnostico existing = buscar(ordem.osId()).orElseThrow(() -> e);
            if (!existing.sagaId().equals(ordem.sagaId()) || !existing.veiculoId().equals(ordem.veiculoId())
                    || !existing.placa().equals(ordem.placa())) {
                throw new IllegalStateException("OS já possui diagnóstico de outro comando", e);
            }
            return false;
        }
    }

    @Override public Optional<OrdemDiagnostico> buscar(UUID osId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder().tableName(ordensTable)
                .key(Map.of("osId", s(osId.toString()))).consistentRead(true).build()).item();
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(new OrdemDiagnostico(UUID.fromString(item.get("osId").s()),
                UUID.fromString(item.get("sagaId").s()), UUID.fromString(item.get("veiculoId").s()),
                item.get("placa").s(), java.time.Instant.parse(item.get("abertaEm").s()), item.get("status").s()));
    }

    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
