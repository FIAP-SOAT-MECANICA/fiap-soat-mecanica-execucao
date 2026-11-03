package br.com.fiap.soat.mecanica.diagnostico;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;

@Testcontainers
class ExecucaoWorkflowLocalStackTest {
    @Container static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    @Test void concluiDiagnosticoEExecucaoComEventosNaOutbox() throws Exception {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (var dynamo = DynamoDbClient.builder().endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion())).credentialsProvider(credentials).build()) {
            createTable(dynamo, "execucao-ordens", "osId");
            createTable(dynamo, "execucao-pecas", "pecaId");
            createTable(dynamo, "execucao-outbox", "eventId");
            createTable(dynamo, "execucao-idempotencia", "messageId");
            var mapper = new ObjectMapper().findAndRegisterModules();
            var repo = new DynamoDiagnosticoRepository(dynamo, mapper, "execucao-ordens",
                    "execucao-idempotencia", "execucao-outbox");
            UUID osId = UUID.randomUUID(), sagaId = UUID.randomUUID(), servicoId = UUID.randomUUID(),
                    pecaId = UUID.randomUUID();
            var event = new MessageEnvelope(UUID.randomUUID(), "DiagnosticoAberto", 1, sagaId, osId,
                    "trace", null, Instant.now(), mapper.createObjectNode().put("osId", osId.toString()));
            assertTrue(repo.abrirSeAusente(new OrdemDiagnostico(osId, sagaId, UUID.randomUUID(),
                    "ABC1D23", Instant.now(), "EM_DIAGNOSTICO"), UUID.randomUUID(), event));
            dynamo.putItem(b -> b.tableName("execucao-pecas").item(Map.of(
                    "pecaId", AttributeValue.fromS(pecaId.toString()),
                    "valorUnitario", AttributeValue.fromN("25.50"))));
            var workflow = new ExecucaoWorkflowService(dynamo, mapper, Clock.systemUTC(), id -> "Troca de óleo",
                    "execucao-ordens", "execucao-pecas", "execucao-outbox");

            var added = workflow.adicionarPrestacao(osId, servicoId, new BigDecimal("80.00"));
            UUID prestacaoId = UUID.fromString((String) ((Map<?, ?>) ((List<?>) added.get("prestacoes")).getFirst()).get("id"));
            assertThrows(IllegalStateException.class, () -> workflow.finalizarPrestacao(prestacaoId, "trace"));
            var withPiece = workflow.adicionarAlocacao(osId, prestacaoId, pecaId, 2);
            UUID alocacaoId = UUID.fromString((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>)
                    ((List<?>) withPiece.get("prestacoes")).getFirst()).get("pecas")).getFirst()).get("id"));
            workflow.inativarAlocacao(alocacaoId);
            workflow.adicionarAlocacao(osId, prestacaoId, pecaId, 2);
            var second = workflow.adicionarPrestacao(osId, UUID.randomUUID(), new BigDecimal("30.00"));
            UUID secondId = UUID.fromString((String) ((Map<?, ?>) ((List<?>) second.get("prestacoes")).get(1)).get("id"));
            workflow.inativarPrestacao(secondId);
            var diagnosed = workflow.concluirDiagnostico(osId, "trace");
            assertEquals("AGUARDANDO_APROVACAO", diagnosed.get("status"));
            assertEquals(1, outboxCount(dynamo, "DiagnosticoConcluido"));
            var diagnosisEvent = dynamo.scan(b -> b.tableName("execucao-outbox")).items().stream()
                    .map(item -> item.get("body").s()).filter(body -> body.contains("DiagnosticoConcluido"))
                    .findFirst().orElseThrow();
            var diagnosisPayload = mapper.readTree(diagnosisEvent).path("payload").path("itens");
            assertEquals(1, diagnosisPayload.size());
            assertEquals(0, new BigDecimal(diagnosisPayload.get(0).path("pecas").get(0)
                    .path("valorUnit").asText()).compareTo(new BigDecimal("25.50")));
            assertFalse(diagnosisEvent.contains("30.00"));
            assertEquals(diagnosed, workflow.concluirDiagnostico(osId, "trace"));
            assertEquals(1, outboxCount(dynamo, "DiagnosticoConcluido"));

            var started = workflow.iniciarExecucao(osId, "trace");
            assertEquals("EM_EXECUCAO", started.get("status"));
            var finished = workflow.finalizarPrestacao(prestacaoId, "trace");
            assertEquals("FINALIZADA", finished.get("status"));
            assertEquals(1, outboxCount(dynamo, "ExecucaoFinalizada"));
            var item = dynamo.getItem(GetItemRequest.builder().tableName("execucao-ordens")
                    .key(Map.of("osId", AttributeValue.fromS(osId.toString()))).build()).item();
            assertFalse(item.containsKey("filaStatus"));
            assertFalse(item.containsKey("filaOrdem"));
        }
    }

    private static long outboxCount(DynamoDbClient dynamo, String type) {
        return dynamo.scan(b -> b.tableName("execucao-outbox")).items().stream()
                .filter(item -> item.get("body").s().contains("\"type\":\"" + type + "\""))
                .count();
    }

    private static void createTable(DynamoDbClient dynamo, String table, String key) {
        dynamo.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName(key).attributeType("S").build())
                .keySchema(KeySchemaElement.builder().attributeName(key).keyType("HASH").build()).build());
    }
}
