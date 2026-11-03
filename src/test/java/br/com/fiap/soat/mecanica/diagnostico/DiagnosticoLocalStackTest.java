package br.com.fiap.soat.mecanica.diagnostico;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Testcontainers
class DiagnosticoLocalStackTest {
    @Container static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SQS,
                    LocalStackContainer.Service.SNS);

    @Test void comandoSqsAbreDiagnosticoUmaVezEPublicaEventoSns() throws Exception {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (var dynamo = DynamoDbClient.builder().endpointOverride(localstack.getEndpoint())
                     .region(Region.of(localstack.getRegion())).credentialsProvider(credentials).build();
             var sqs = SqsClient.builder().endpointOverride(localstack.getEndpoint())
                     .region(Region.of(localstack.getRegion())).credentialsProvider(credentials).build();
             var sns = SnsClient.builder().endpointOverride(localstack.getEndpoint())
                     .region(Region.of(localstack.getRegion())).credentialsProvider(credentials).build()) {
            createTable(dynamo, "execucao-ordens", "osId");
            createTable(dynamo, "execucao-idempotencia", "messageId");
            createTable(dynamo, "execucao-outbox", "eventId");
            createTable(dynamo, "execucao-pecas", "pecaId");
            dynamo.createTable(CreateTableRequest.builder().tableName("execucao-reservas")
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("osId").attributeType("S").build(),
                            AttributeDefinition.builder().attributeName("pecaId").attributeType("S").build())
                    .keySchema(KeySchemaElement.builder().attributeName("osId").keyType("HASH").build(),
                            KeySchemaElement.builder().attributeName("pecaId").keyType("RANGE").build()).build());
            String commandQueue = sqs.createQueue(CreateQueueRequest.builder().queueName("execucao-comandos").build()).queueUrl();
            String eventQueue = sqs.createQueue(CreateQueueRequest.builder().queueName("os-saga-respostas").build()).queueUrl();
            String eventQueueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(eventQueue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            String topic = sns.createTopic(b -> b.name("execucao-eventos")).topicArn();
            sns.subscribe(SubscribeRequest.builder().topicArn(topic).protocol("sqs").endpoint(eventQueueArn)
                    .attributes(Map.of("RawMessageDelivery", "true")).build());

            var mapper = new ObjectMapper().findAndRegisterModules();
            var repo = new DynamoDiagnosticoRepository(dynamo, mapper, "execucao-ordens",
                    "execucao-idempotencia", "execucao-outbox");
            var useCase = new AbrirDiagnosticoUseCase(repo, mapper, Clock.systemUTC());
            var stock = new EstoqueCommandHandler(
                    new br.com.fiap.soat.mecanica.estoque.EstoqueService(
                            new br.com.fiap.soat.mecanica.estoque.DynamoEstoqueRepository(dynamo,
                                    "execucao-pecas", "execucao-reservas")),
                    new EventoOutbox(dynamo, mapper, "execucao-outbox"), mapper, Clock.systemUTC());
            var consumer = new DiagnosticoCommandConsumer(sqs, mapper, useCase, stock, commandQueue);
            var publisher = new DiagnosticoOutboxPublisher(dynamo, sns, "execucao-outbox", topic);
            UUID osId = UUID.randomUUID();
            UUID commandId = UUID.randomUUID();
            var command = new MessageEnvelope(commandId, "AbrirDiagnostico", 1, UUID.randomUUID(), osId,
                    "integration-trace", null, Instant.now(), mapper.createObjectNode()
                    .put("osId", osId.toString()).put("veiculoId", UUID.randomUUID().toString()).put("placa", "ABC1D23"));
            String body = mapper.writeValueAsString(command);
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(commandQueue).messageBody(body).build());
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(commandQueue).messageBody(body).build());
            consumer.poll();
            assertTrue(repo.buscar(osId).isPresent());
            assertEquals(1, dynamo.scan(b -> b.tableName("execucao-idempotencia")).count());
            assertEquals(1, dynamo.scan(b -> b.tableName("execucao-outbox")).count());
            publisher.publishPending();
            var events = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(eventQueue)
                    .waitTimeSeconds(5).maxNumberOfMessages(10).build()).messages();
            assertEquals(1, events.size());
            MessageEnvelope event = mapper.readValue(events.getFirst().body(), MessageEnvelope.class);
            assertEquals("DiagnosticoAberto", event.type());
            assertEquals(commandId, event.causationId());
            assertEquals(osId, event.osId());
            assertEquals("integration-trace", event.correlationId());
            assertEquals(0, dynamo.scan(b -> b.tableName("execucao-outbox")).count());

            UUID pecaId = UUID.randomUUID();
            dynamo.putItem(b -> b.tableName("execucao-pecas").item(Map.of(
                    "pecaId", AttributeValue.fromS(pecaId.toString()),
                    "qtdEstoque", AttributeValue.fromN("1"),
                    "qtdDisponivel", AttributeValue.fromN("1"),
                    "qtdReservada", AttributeValue.fromN("0"))));
            var reserva = new MessageEnvelope(UUID.randomUUID(), "ReservarPecas", 1, command.sagaId(), osId,
                    "integration-trace", commandId, Instant.now(), mapper.createObjectNode()
                    .put("osId", osId.toString()).set("pecas", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("pecaId", pecaId.toString()).put("qtd", 1))));
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(commandQueue)
                    .messageBody(mapper.writeValueAsString(reserva)).build());
            consumer.poll();
            assertEquals("0", dynamo.getItem(GetItemRequest.builder().tableName("execucao-pecas")
                    .key(Map.of("pecaId", AttributeValue.fromS(pecaId.toString()))).build())
                    .item().get("qtdDisponivel").n());
            publisher.publishPending();
            var stockEvents = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(eventQueue)
                    .waitTimeSeconds(5).maxNumberOfMessages(10).build()).messages();
            assertEquals("PecasReservadas", mapper.readValue(stockEvents.getFirst().body(), MessageEnvelope.class).type());
        }
    }

    private static void createTable(DynamoDbClient dynamo, String table, String key) {
        dynamo.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName(key).attributeType("S").build())
                .keySchema(KeySchemaElement.builder().attributeName(key).keyType("HASH").build()).build());
    }
}
