package br.com.fiap.soat.mecanica.diagnostico;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@Component
class DiagnosticoOutboxPublisher {
    private final DynamoDbClient dynamo;
    private final SnsClient sns;
    private final String table;
    private final String topicArn;

    DiagnosticoOutboxPublisher(DynamoDbClient dynamo, SnsClient sns,
            @Value("${execucao.diagnostico.outbox-table}") String table,
            @Value("${execucao.diagnostico.event-topic-arn:}") String topicArn) {
        this.dynamo = dynamo;
        this.sns = sns;
        this.table = table;
        this.topicArn = topicArn;
    }

    @Scheduled(fixedDelayString = "${execucao.diagnostico.outbox-delay-ms:2000}")
    void publishPending() {
        if (topicArn.isBlank()) return;
        var items = dynamo.scan(ScanRequest.builder().tableName(table).limit(25).build()).items();
        for (var item : items) {
            try {
                sns.publish(PublishRequest.builder().topicArn(topicArn).message(item.get("body").s()).build());
                dynamo.deleteItem(DeleteItemRequest.builder().tableName(table)
                        .key(Map.of("eventId", AttributeValue.builder().s(item.get("eventId").s()).build()))
                        .conditionExpression("attribute_exists(eventId)").build());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Falha ao publicar evento de diagnóstico", e);
            }
        }
    }
}
