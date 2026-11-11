package br.com.fiap.soat.mecanica.estoque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import br.com.fiap.soat.mecanica.diagnostico.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoEstoqueRepositoryTest {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private DynamoDbClient client;
    private EstoqueService service;
    private UUID pecaId;
    private String outboxTable;
    private String idempotenciaTable;
    private PecaRepository pecas;

    @BeforeEach
    void setUp() {
        client = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String pecasTable = "pecas" + suffix;
        String reservasTable = "reservas" + suffix;
        outboxTable = "outbox" + suffix;
        idempotenciaTable = "idem" + suffix;
        createTable(pecasTable, "pecaId", null);
        createTable(reservasTable, "osId", "pecaId");
        createTable(outboxTable, "eventId", null);
        createTable(idempotenciaTable, "messageId", null);
        pecaId = UUID.randomUUID();
        client.putItem(b -> b.tableName(pecasTable).item(Map.of(
                "pecaId", AttributeValue.fromS(pecaId.toString()),
                "qtdEstoque", AttributeValue.fromN("1"),
                "qtdDisponivel", AttributeValue.fromN("1"),
                "qtdReservada", AttributeValue.fromN("0"))));
        service = new EstoqueService(new DynamoEstoqueRepository(client, pecasTable, reservasTable,
                idempotenciaTable, outboxTable, new ObjectMapper().findAndRegisterModules()));
        pecas = new DynamoPecaRepository(client, pecasTable);
        this.pecasTable = pecasTable;
    }

    private String pecasTable;

    @Test
    void duasOsDisputamUltimaPecaSomenteUmaReserva() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> attempt(start, a);
            Callable<Boolean> second = () -> attempt(start, b);
            Future<Boolean> one = pool.submit(first);
            Future<Boolean> two = pool.submit(second);
            start.countDown();
            assertEquals(1, (one.get() ? 1 : 0) + (two.get() ? 1 : 0));
        }
        assertEquals("0", piece().get("qtdDisponivel").n());
        assertEquals("1", piece().get("qtdReservada").n());
        assertEquals("1", piece().get("qtdEstoque").n());
    }

    @Test
    void reservaDuplicadaEIdempotenteEConfirmacaoBaixaUmaVez() {
        UUID osId = UUID.randomUUID();
        service.reservar(osId, Map.of(pecaId, 1));
        service.reservar(osId, Map.of(pecaId, 1));
        service.confirmar(osId);
        service.confirmar(osId);
        assertEquals("0", piece().get("qtdEstoque").n());
        assertEquals("0", piece().get("qtdReservada").n());
        assertEquals("0", piece().get("qtdDisponivel").n());
        assertThrows(EstoqueException.class, () -> service.liberar(osId));
    }

    @Test
    void liberarDevolveDisponibilidadeERecusaPedidoDivergente() {
        UUID osId = UUID.randomUUID();
        service.reservar(osId, Map.of(pecaId, 1));
        assertThrows(EstoqueException.class, () -> service.reservar(osId, Map.of(pecaId, 2)));
        service.liberar(osId);
        service.liberar(osId);
        assertEquals("1", piece().get("qtdEstoque").n());
        assertEquals("1", piece().get("qtdDisponivel").n());
        assertEquals("0", piece().get("qtdReservada").n());
        assertThrows(EstoqueException.class, () -> service.reservar(osId, Map.of(pecaId, 1)));
    }

    @Test
    void faltaDeUmaPecaNaoReservaNenhumaDasOutras() {
        UUID semEstoque = UUID.randomUUID();
        client.putItem(b -> b.tableName(pecasTable).item(Map.of(
                "pecaId", AttributeValue.fromS(semEstoque.toString()),
                "qtdEstoque", AttributeValue.fromN("0"),
                "qtdDisponivel", AttributeValue.fromN("0"),
                "qtdReservada", AttributeValue.fromN("0"))));
        var shortage = assertThrows(EstoqueSemDisponibilidadeException.class, () -> service.reservar(
                UUID.randomUUID(), Map.of(pecaId, 1, semEstoque, 1)));
        assertEquals(Map.of(semEstoque, 1), shortage.faltantes());
        assertEquals("1", piece().get("qtdDisponivel").n());
        assertEquals("0", piece().get("qtdReservada").n());
    }

    @Test
    void reservaELiberacaoGravamEventoNaMesmaTransacao() {
        UUID osId = UUID.randomUUID();
        MessageEnvelope reservada = event(osId, "PecasReservadas");
        service.reservar(osId, Map.of(pecaId, 1), reservada);
        assertFalse(outbox(reservada.messageId()).isEmpty());
        assertEquals("0", piece().get("qtdDisponivel").n());
        MessageEnvelope liberada = event(osId, "ReservaPecasLiberada");
        service.liberar(osId, liberada);
        assertFalse(outbox(liberada.messageId()).isEmpty());
        assertEquals("1", piece().get("qtdDisponivel").n());
        client.deleteItem(b -> b.tableName(outboxTable)
                .key(Map.of("eventId", AttributeValue.fromS(reservada.messageId().toString()))));
        service.reservar(osId, Map.of(pecaId, 1), reservada);
        assertEquals(true, outbox(reservada.messageId()).isEmpty());
        assertEquals("1", piece().get("qtdDisponivel").n());
    }

    @Test
    void falhaNoOutboxReverteReserva() {
        UUID osId = UUID.randomUUID();
        MessageEnvelope event = event(osId, "PecasReservadas");
        client.deleteTable(b -> b.tableName(outboxTable));
        assertThrows(RuntimeException.class, () -> service.reservar(osId, Map.of(pecaId, 1), event));
        assertEquals("1", piece().get("qtdDisponivel").n());
        assertEquals("0", piece().get("qtdReservada").n());
    }

    @Test
    void recusaTemMarcadorDuravelAposPublicacaoDoEvento() {
        var event = event(UUID.randomUUID(), "ReservaPecasRecusada");
        service.registrarResultado(event);
        assertEquals(true, service.jaProcessado(event.causationId()));
        client.deleteItem(b -> b.tableName(outboxTable)
                .key(Map.of("eventId", AttributeValue.fromS(event.messageId().toString()))));
        service.registrarResultado(event);
        assertEquals(true, outbox(event.messageId()).isEmpty());
        service.reservar(event.osId(), Map.of(pecaId, 1), event);
        assertEquals("1", piece().get("qtdDisponivel").n());
    }

    @Test
    void crudDePecasPreservaReservasEQuantidades() {
        UUID id = UUID.randomUUID();
        var criada = new Peca(id, "Filtro", "Filtro de óleo", new BigDecimal("20.00"), 3, 3, 0);
        pecas.criar(criada);
        assertEquals(criada, pecas.buscar(id).orElseThrow());
        assertEquals(2, pecas.listar().size());
        var atualizada = pecas.atualizar(new Peca(id, "Filtro novo", "Filtro de óleo",
                new BigDecimal("22.00"), 4, 4, 0));
        assertEquals(4, atualizada.qtdDisponivel());
        UUID osId = UUID.randomUUID();
        service.reservar(osId, Map.of(id, 2));
        assertThrows(EstoqueException.class, () -> pecas.atualizar(new Peca(id, "Filtro",
                "Filtro de óleo", BigDecimal.ONE, 1, 1, 0)));
        service.liberar(osId);
        assertEquals(4, pecas.buscar(id).orElseThrow().qtdDisponivel());
    }

    private MessageEnvelope event(UUID osId, String type) {
        return new MessageEnvelope(UUID.randomUUID(), type, 1, UUID.randomUUID(), osId,
                "trace", UUID.randomUUID(), Instant.now(), new ObjectMapper().createObjectNode());
    }

    private Map<String, AttributeValue> outbox(UUID id) {
        return client.getItem(b -> b.tableName(outboxTable)
                .key(Map.of("eventId", AttributeValue.fromS(id.toString()))).consistentRead(true)).item();
    }

    private boolean attempt(CountDownLatch start, UUID osId) throws InterruptedException {
        start.await();
        try {
            service.reservar(osId, Map.of(pecaId, 1));
            return true;
        } catch (EstoqueException expected) {
            return false;
        }
    }

    private Map<String, AttributeValue> piece() {
        return client.getItem(b -> b.tableName(pecasTable)
                .key(Map.of("pecaId", AttributeValue.fromS(pecaId.toString())))
                .consistentRead(true)).item();
    }

    private void createTable(String table, String partition, String sort) {
        var attributes = new java.util.ArrayList<AttributeDefinition>();
        var keys = new java.util.ArrayList<KeySchemaElement>();
        attributes.add(AttributeDefinition.builder()
                .attributeName(partition).attributeType(ScalarAttributeType.S).build());
        keys.add(KeySchemaElement.builder()
                .attributeName(partition).keyType(KeyType.HASH).build());
        if (sort != null) {
            attributes.add(AttributeDefinition.builder()
                    .attributeName(sort).attributeType(ScalarAttributeType.S).build());
            keys.add(KeySchemaElement.builder()
                    .attributeName(sort).keyType(KeyType.RANGE).build());
        }
        client.createTable(CreateTableRequest.builder().tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributes).keySchema(keys).build());
    }
}
