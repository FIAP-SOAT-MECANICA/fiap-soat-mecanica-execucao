package br.com.fiap.soat.mecanica.consultas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoConsultaExecucaoRepositoryTest {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private DynamoDbClient dynamo;
    private DynamoConsultaExecucaoRepository repository;
    private String table;

    @BeforeEach
    void setUp() {
        dynamo = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        table = "ordens" + UUID.randomUUID().toString().replace("-", "");
        var stringAttribute = List.of("osId", "filaStatus", "filaOrdem").stream()
                .map(name -> AttributeDefinition.builder()
                        .attributeName(name).attributeType(ScalarAttributeType.S).build())
                .toList();
        var gsi = GlobalSecondaryIndex.builder().indexName(FilaKey.INDEX_NAME)
                .keySchema(key("filaStatus", KeyType.HASH), key("filaOrdem", KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build();
        dynamo.createTable(CreateTableRequest.builder().tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST).attributeDefinitions(stringAttribute)
                .keySchema(key("osId", KeyType.HASH)).globalSecondaryIndexes(gsi).build());
        repository = new DynamoConsultaExecucaoRepository(dynamo, table);
    }

    @Test
    void filaSeguePrioridadeDataIdComPaginacaoPorCursor() {
        UUID exec = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID diagnosis = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        put(exec, "EM_EXECUCAO", "2026-11-03T12:00:00.100Z", List.of());
        put(approval, "AGUARDANDO_APROVACAO", "2026-11-01T12:00:00Z", List.of());
        put(diagnosis, "EM_DIAGNOSTICO", "2026-10-01T12:00:00Z", List.of());
        put(finished, "FINALIZADA", "2026-09-01T12:00:00Z", List.of());

        FilaPage first = repository.listarFila(2, null);
        assertEquals(List.of(exec, approval), first.itens().stream().map(FilaItem::osId).toList());
        FilaPage second = repository.listarFila(2, first.proximoCursor());
        assertEquals(List.of(diagnosis), second.itens().stream().map(FilaItem::osId).toList());
        assertNull(second.proximoCursor());
        assertThrows(IllegalArgumentException.class, () -> repository.listarFila(2, "bad cursor"));
    }

    @Test
    void tempoMedioAgrupaPrestacoesValidasEIgnoraDuracoesInvalidas() {
        UUID service = UUID.randomUUID();
        put(UUID.randomUUID(), "FINALIZADA", "2026-11-01T00:00:00Z", List.of(
                prestation(service, "Troca de oleo", "2026-11-01T10:00:00Z", "2026-11-01T10:01:00Z"),
                prestation(service, "Troca de oleo", "2026-11-01T12:00:00Z", "2026-11-01T12:02:00Z"),
                prestation(service, "Troca de oleo", "2026-11-01T13:00:00Z", "2026-11-01T13:00:00Z")));
        put(UUID.randomUUID(), "EM_DIAGNOSTICO", "2026-11-02T00:00:00Z", List.of(
                Map.of("servicoId", s(service.toString()), "nomeServico", s("Troca de oleo"),
                        "dataInicio", s("2026-11-02T10:00:00Z"))));

        assertEquals(List.of(new TempoMedioServico(service, "Troca de oleo", 90.0, 2)),
                repository.temposMedios());
    }

    @Test
    void filaKeyUsaPrecisaoFixaParaOrdemCronologica() {
        UUID id = UUID.randomUUID();
        String whole = FilaKey.attributes("EM_DIAGNOSTICO", "2026-11-03T12:00:00Z", id)
                .get("filaOrdem").s();
        String fractional = FilaKey.attributes("EM_DIAGNOSTICO", "2026-11-03T12:00:00.1Z", id)
                .get("filaOrdem").s();
        assertEquals(true, whole.compareTo(fractional) < 0);
        assertEquals(Map.of(), FilaKey.attributes("FINALIZADA", "2026-11-03T12:00:00Z", id));
    }

    private void put(UUID id, String status, String opened, List<Map<String, AttributeValue>> prestations) {
        Map<String, AttributeValue> item = new java.util.HashMap<>();
        item.put("osId", s(id.toString()));
        item.put("status", s(status));
        item.put("placa", s("ABC1D23"));
        item.put("abertaEm", s(opened));
        item.putAll(FilaKey.attributes(status, opened, id));
        if (!prestations.isEmpty()) {
            item.put("prestacoes", AttributeValue.builder().l(prestations.stream()
                    .map(fields -> AttributeValue.builder().m(fields).build()).toList()).build());
        }
        dynamo.putItem(b -> b.tableName(table).item(item));
    }

    private static Map<String, AttributeValue> prestation(UUID service, String name, String start, String end) {
        return Map.of("servicoId", s(service.toString()), "nomeServico", s(name),
                "dataInicio", s(start), "dataFim", s(end));
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeValue s(String value) { return AttributeValue.fromS(value); }
}
