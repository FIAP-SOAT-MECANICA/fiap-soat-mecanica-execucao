package br.com.fiap.soat.mecanica.diagnostico;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

class DynamoDiagnosticoRepositoryTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoDiagnosticoRepository repo = new DynamoDiagnosticoRepository(dynamo, new ObjectMapper().findAndRegisterModules(),
            "execucao-ordens", "execucao-idempotencia", "execucao-outbox");
    private final UUID osId = UUID.randomUUID();
    private final UUID sagaId = UUID.randomUUID();
    private final UUID veiculoId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-03T12:00:00Z");

    @Test void gravaOrdemIdempotenciaEEventoEmUmaTransacaoCondicional() {
        var ordem = order();
        var event = event();
        assertTrue(repo.abrirSeAusente(ordem, UUID.randomUUID(), event));
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        var writes = captor.getValue().transactItems();
        assertEquals(3, writes.size());
        assertEquals("execucao-ordens", writes.get(0).put().tableName());
        assertEquals("attribute_not_exists(osId)", writes.get(0).put().conditionExpression());
        assertEquals("execucao-idempotencia", writes.get(1).put().tableName());
        assertEquals("attribute_not_exists(messageId)", writes.get(1).put().conditionExpression());
        assertEquals("execucao-outbox", writes.get(2).put().tableName());
        assertTrue(writes.get(2).put().item().get("body").s().contains("DiagnosticoAberto"));
    }

    @Test void redeliveryDaMesmaSagaNaoGravaNovoEvento() {
        doThrow(TransactionCanceledException.builder().message("conditional check failed").build())
                .when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        when(dynamo.getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "osId", s(osId.toString()), "sagaId", s(sagaId.toString()),
                        "veiculoId", s(veiculoId.toString()), "placa", s("ABC1D23"),
                        "abertaEm", s(now.toString()), "status", s("EM_DIAGNOSTICO"))).build());
        assertFalse(repo.abrirSeAusente(order(), UUID.randomUUID(), event()));
    }

    @Test void conflitoDeSagaNaoETratadoComoRedelivery() {
        doThrow(TransactionCanceledException.builder().message("conditional check failed").build())
                .when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        when(dynamo.getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "osId", s(osId.toString()), "sagaId", s(UUID.randomUUID().toString()),
                        "veiculoId", s(veiculoId.toString()), "placa", s("ABC1D23"),
                        "abertaEm", s(now.toString()), "status", s("EM_DIAGNOSTICO"))).build());
        assertThrows(IllegalStateException.class, () -> repo.abrirSeAusente(order(), UUID.randomUUID(), event()));
    }

    @Test void consultaOrdemEmExecucaoEAberturaExigeEstadoInicial() {
        when(dynamo.getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "osId", s(osId.toString()), "sagaId", s(sagaId.toString()),
                        "veiculoId", s(veiculoId.toString()), "placa", s("ABC1D23"),
                        "abertaEm", s(now.toString()), "status", s("EM_EXECUCAO"))).build());
        assertEquals("EM_EXECUCAO", repo.buscar(osId).orElseThrow().status());
        when(dynamo.getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "osId", s(osId.toString()), "sagaId", s(sagaId.toString()),
                        "veiculoId", s(veiculoId.toString()), "placa", s("ABC1D23"),
                        "abertaEm", s(now.toString()), "status", s("FINALIZADA"))).build());
        assertEquals("FINALIZADA", repo.buscar(osId).orElseThrow().status());
        assertThrows(IllegalArgumentException.class, () -> new OrdemDiagnostico(
                osId, sagaId, veiculoId, "ABC1D23", now, null));
        assertThrows(IllegalArgumentException.class, () -> repo.abrirSeAusente(
                new OrdemDiagnostico(osId, sagaId, veiculoId, "ABC1D23", now, "FINALIZADA"),
                UUID.randomUUID(), event()));
    }

    private OrdemDiagnostico order() {
        return new OrdemDiagnostico(osId, sagaId, veiculoId, "ABC1D23", now, "EM_DIAGNOSTICO");
    }
    private MessageEnvelope event() {
        return new MessageEnvelope(UUID.randomUUID(), "DiagnosticoAberto", 1, sagaId, osId,
                "trace-1", UUID.randomUUID(), now, new ObjectMapper().createObjectNode().put("osId", osId.toString()));
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
