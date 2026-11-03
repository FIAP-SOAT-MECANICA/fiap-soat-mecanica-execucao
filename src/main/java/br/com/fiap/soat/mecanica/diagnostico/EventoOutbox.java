package br.com.fiap.soat.mecanica.diagnostico;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Component
class EventoOutbox {
    private final DynamoDbClient dynamo;
    private final ObjectMapper mapper;
    private final String table;

    EventoOutbox(DynamoDbClient dynamo, ObjectMapper mapper,
            @Value("${execucao.diagnostico.outbox-table}") String table) {
        this.dynamo = dynamo;
        this.mapper = mapper;
        this.table = table;
    }

    void registrar(MessageEnvelope event) {
        String body;
        try { body = mapper.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Falha ao serializar evento", e); }
        try {
            dynamo.putItem(PutItemRequest.builder().tableName(table).item(Map.of(
                    "eventId", AttributeValue.fromS(event.messageId().toString()),
                    "body", AttributeValue.fromS(body),
                    "createdAt", AttributeValue.fromS(event.occurredAt().toString())))
                    .conditionExpression("attribute_not_exists(eventId)").build());
        } catch (ConditionalCheckFailedException ignored) {
            // The same command already recorded this event.
        }
    }
}
