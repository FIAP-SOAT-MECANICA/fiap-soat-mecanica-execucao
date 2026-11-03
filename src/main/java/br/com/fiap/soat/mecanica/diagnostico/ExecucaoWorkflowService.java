package br.com.fiap.soat.mecanica.diagnostico;

import br.com.fiap.soat.mecanica.consultas.FilaKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Owns diagnosis and prestation writes on one order item with optimistic locking. */
@Service
public class ExecucaoWorkflowService {
    private final DynamoDbClient dynamo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CatalogoServico catalogo;
    private final String ordensTable;
    private final String pecasTable;
    private final String outboxTable;

    public ExecucaoWorkflowService(DynamoDbClient dynamo, ObjectMapper mapper, Clock clock,
            CatalogoServico catalogo,
            @Value("${execucao.diagnostico.ordens-table}") String ordensTable,
            @Value("${execucao.estoque.pecas-table}") String pecasTable,
            @Value("${execucao.diagnostico.outbox-table}") String outboxTable) {
        this.dynamo = dynamo; this.mapper = mapper; this.clock = clock; this.catalogo = catalogo;
        this.ordensTable = ordensTable; this.pecasTable = pecasTable; this.outboxTable = outboxTable;
    }

    public Map<String, Object> adicionarPrestacao(UUID osId, UUID servicoId, BigDecimal precoMdo) {
        if (servicoId == null || precoMdo == null || precoMdo.signum() <= 0)
            throw new IllegalArgumentException("Serviço e preço de mão de obra positivos são obrigatórios");
        String nome = catalogo.nomeAtivo(servicoId);
        return change(osId, order -> {
            requireStatus(order, "EM_DIAGNOSTICO");
            List<Map<String, AttributeValue>> prestacoes = prestacoes(order);
            if (prestacoes.stream().anyMatch(p -> active(p) && servicoId.toString().equals(str(p, "servicoId"))))
                throw new IllegalStateException("Serviço já adicionado na OS");
            prestacoes.add(new HashMap<>(Map.of(
                    "id", s(UUID.randomUUID().toString()), "servicoId", s(servicoId.toString()),
                    "nomeServico", s(nome), "precoMdo", s(precoMdo.toPlainString()),
                    "ativo", AttributeValue.fromBool(true), "pecas", list(List.of()))));
            order.put("prestacoes", listMaps(prestacoes));
            return null;
        });
    }

    public Map<String, Object> inativarPrestacao(UUID prestacaoId) {
        return change(findOrderIdForPrestacao(prestacaoId), order -> {
            requireStatus(order, "EM_DIAGNOSTICO");
            var prestacoes = prestacoes(order);
            var prestacao = find(prestacoes, prestacaoId);
            if (!active(prestacao)) throw new IllegalStateException("Prestação já inativa");
            prestacao.put("ativo", AttributeValue.fromBool(false));
            var pecas = pecas(prestacao);
            pecas.forEach(p -> p.put("ativo", AttributeValue.fromBool(false)));
            prestacao.put("pecas", listMaps(pecas));
            order.put("prestacoes", listMaps(prestacoes));
            return null;
        });
    }

    public Map<String, Object> adicionarAlocacao(UUID osId, UUID prestacaoId, UUID pecaId, int qtd) {
        if (pecaId == null || qtd <= 0) throw new IllegalArgumentException("Peça e quantidade positiva são obrigatórias");
        var piece = dynamo.getItem(GetItemRequest.builder().tableName(pecasTable)
                .key(Map.of("pecaId", s(pecaId.toString()))).consistentRead(true).build()).item();
        if (piece == null || piece.isEmpty()) throw new IllegalArgumentException("Peça não encontrada");
        String valorUnit = scalar(piece, "valorUnitario");
        if (valorUnit == null || new BigDecimal(valorUnit).signum() < 0)
            throw new IllegalStateException("Peça sem valor unitário válido");
        return change(osId, order -> {
            requireStatus(order, "EM_DIAGNOSTICO");
            var prestacoes = prestacoes(order);
            var prestacao = find(prestacoes, prestacaoId);
            if (!active(prestacao)) throw new IllegalStateException("Prestação inativa");
            var pecas = pecas(prestacao);
            if (pecas.stream().anyMatch(p -> active(p) && pecaId.toString().equals(str(p, "pecaId"))))
                throw new IllegalStateException("Peça já alocada nessa prestação");
            pecas.add(new HashMap<>(Map.of("id", s(UUID.randomUUID().toString()),
                    "pecaId", s(pecaId.toString()), "qtd", AttributeValue.fromN(Integer.toString(qtd)),
                    "valorUnit", s(valorUnit), "ativo", AttributeValue.fromBool(true))));
            prestacao.put("pecas", listMaps(pecas));
            order.put("prestacoes", listMaps(prestacoes));
            return null;
        });
    }

    public Map<String, Object> inativarAlocacao(UUID alocacaoId) {
        return change(findOrderIdForAlocacao(alocacaoId), order -> {
            requireStatus(order, "EM_DIAGNOSTICO");
            var prestacoes = prestacoes(order);
            boolean found = false;
            for (var p : prestacoes) {
                var allocations = pecas(p);
                for (var item : allocations) if (alocacaoId.toString().equals(str(item, "id"))) {
                    if (!active(item)) throw new IllegalStateException("Alocação já inativa");
                    item.put("ativo", AttributeValue.fromBool(false));
                    p.put("pecas", listMaps(allocations));
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalArgumentException("Alocação não encontrada");
            order.put("prestacoes", listMaps(prestacoes));
            return null;
        });
    }

    public Map<String, Object> concluirDiagnostico(UUID osId, String correlationId) {
        return change(osId, order -> {
            if ("AGUARDANDO_APROVACAO".equals(str(order, "status"))) return NO_CHANGE;
            requireStatus(order, "EM_DIAGNOSTICO");
            var ativos = prestacoes(order).stream().filter(ExecucaoWorkflowService::active).toList();
            if (ativos.isEmpty()) throw new IllegalStateException("Diagnóstico sem prestações ativas");
            var items = mapper.createArrayNode();
            for (var p : ativos) {
                var item = items.addObject().put("servicoId", str(p, "servicoId"))
                        .put("precoMdo", new BigDecimal(str(p, "precoMdo")));
                var parts = item.putArray("pecas");
                for (var part : pecas(p)) if (active(part)) parts.addObject()
                        .put("pecaId", str(part, "pecaId"))
                        .put("qtd", Integer.parseInt(part.get("qtd").n()))
                        .put("valorUnit", new BigDecimal(str(part, "valorUnit")));
            }
            order.put("status", s("AGUARDANDO_APROVACAO"));
            order.put("diagnosticadoEm", s(clock.instant().toString()));
            return event(order, "DiagnosticoConcluido", correlationId,
                    mapper.createObjectNode().put("osId", osId.toString()).set("itens", items));
        });
    }

    /** Called only after stock reservation confirmation; retry is safe if this write fails. */
    public Map<String, Object> iniciarExecucao(UUID osId, String correlationId) {
        return change(osId, order -> {
            if ("EM_EXECUCAO".equals(str(order, "status"))) return NO_CHANGE;
            requireStatus(order, "AGUARDANDO_APROVACAO");
            String now = clock.instant().toString();
            var prestacoes = prestacoes(order);
            for (var p : prestacoes) if (active(p)) p.put("dataInicio", s(now));
            order.put("prestacoes", listMaps(prestacoes));
            order.put("status", s("EM_EXECUCAO"));
            order.put("iniciadaEm", s(now));
            return event(order, "ExecucaoIniciada", correlationId,
                    mapper.createObjectNode().put("osId", osId.toString()));
        });
    }

    public Map<String, Object> finalizarPrestacao(UUID prestacaoId, String correlationId) {
        return change(findOrderIdForPrestacao(prestacaoId), order -> {
            requireStatus(order, "EM_EXECUCAO");
            var prestacoes = prestacoes(order);
            var prestacao = find(prestacoes, prestacaoId);
            if (!active(prestacao) || str(prestacao, "dataInicio") == null || str(prestacao, "dataFim") != null)
                throw new IllegalStateException("Prestação não pode ser finalizada");
            prestacao.put("dataFim", s(clock.instant().toString()));
            order.put("prestacoes", listMaps(prestacoes));
            var ativos = prestacoes.stream().filter(ExecucaoWorkflowService::active).toList();
            if (ativos.stream().anyMatch(p -> str(p, "dataFim") == null)) return null;
            order.put("status", s("FINALIZADA"));
            order.put("finalizadaEm", s(clock.instant().toString()));
            var tempos = mapper.createArrayNode();
            for (var p : ativos) tempos.addObject().put("servicoId", str(p, "servicoId"))
                    .put("segundos", Duration.between(Instant.parse(str(p, "dataInicio")),
                            Instant.parse(str(p, "dataFim"))).toSeconds());
            return event(order, "ExecucaoFinalizada", correlationId,
                    mapper.createObjectNode().put("osId", str(order, "osId")).set("tempos", tempos));
        });
    }

    private static final MessageEnvelope NO_CHANGE = new MessageEnvelope(null, null, 0, null, null,
            null, null, null, null);

    private Map<String, Object> change(UUID osId, Function<Map<String, AttributeValue>, MessageEnvelope> mutation) {
        var original = dynamo.getItem(GetItemRequest.builder().tableName(ordensTable)
                .key(Map.of("osId", s(osId.toString()))).consistentRead(true).build()).item();
        if (original == null || original.isEmpty()) throw new IllegalArgumentException("Execução não encontrada");
        var updated = new HashMap<>(original);
        MessageEnvelope event = mutation.apply(updated);
        if (event == NO_CHANGE) return view(original);
        long version = original.containsKey("version") ? Long.parseLong(original.get("version").n()) : 0;
        updated.put("version", AttributeValue.fromN(Long.toString(version + 1)));
        updated.remove("filaStatus"); updated.remove("filaOrdem");
        updated.putAll(FilaKey.attributes(str(updated, "status"), str(updated, "abertaEm"), osId));
        var orderPut = Put.builder().tableName(ordensTable).item(updated)
                .conditionExpression("#v = :old OR attribute_not_exists(#v)")
                .expressionAttributeNames(Map.of("#v", "version"))
                .expressionAttributeValues(Map.of(":old", AttributeValue.fromN(Long.toString(version)))).build();
        var writes = new ArrayList<TransactWriteItem>();
        writes.add(TransactWriteItem.builder().put(orderPut).build());
        if (event != null) {
            String body;
            try { body = mapper.writeValueAsString(event); }
            catch (Exception e) { throw new IllegalStateException("Falha ao serializar evento", e); }
            writes.add(TransactWriteItem.builder().put(Put.builder().tableName(outboxTable)
                    .item(Map.of("eventId", s(event.messageId().toString()), "body", s(body),
                            "createdAt", s(event.occurredAt().toString())))
                    .conditionExpression("attribute_not_exists(eventId)").build()).build());
        }
        try { dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build()); }
        catch (TransactionCanceledException e) { throw new IllegalStateException("Execução alterada simultaneamente; tente novamente", e); }
        return view(updated);
    }

    private MessageEnvelope event(Map<String, AttributeValue> order, String type, String correlationId, JsonNode payload) {
        return new MessageEnvelope(UUID.randomUUID(), type, 1, UUID.fromString(str(order, "sagaId")),
                UUID.fromString(str(order, "osId")), correlationId, null, clock.instant(), payload);
    }

    private UUID findOrderIdForPrestacao(UUID id) { return findOrderId(id, false); }
    private UUID findOrderIdForAlocacao(UUID id) { return findOrderId(id, true); }

    // The lookup is bounded by page size. A dedicated GSI can replace it as volume grows.
    private UUID findOrderId(UUID id, boolean allocation) {
        var request = software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                .tableName(ordensTable).projectionExpression("osId, prestacoes").build();
        do {
            var page = dynamo.scan(request);
            for (var item : page.items()) for (var p : prestacoes(item)) {
                if (!allocation && id.toString().equals(str(p, "id"))) return UUID.fromString(str(item, "osId"));
                if (allocation && pecas(p).stream().anyMatch(a -> id.toString().equals(str(a, "id"))))
                    return UUID.fromString(str(item, "osId"));
            }
            if (page.lastEvaluatedKey() == null || page.lastEvaluatedKey().isEmpty()) break;
            request = request.toBuilder().exclusiveStartKey(page.lastEvaluatedKey()).build();
        } while (true);
        throw new IllegalArgumentException(allocation ? "Alocação não encontrada" : "Prestação não encontrada");
    }

    private static void requireStatus(Map<String, AttributeValue> order, String expected) {
        if (!expected.equals(str(order, "status"))) throw new IllegalStateException("Execução deve estar em " + expected);
    }
    private static List<Map<String, AttributeValue>> prestacoes(Map<String, AttributeValue> item) {
        return maps(item.get("prestacoes"));
    }
    private static List<Map<String, AttributeValue>> pecas(Map<String, AttributeValue> item) {
        return maps(item.get("pecas"));
    }
    private static List<Map<String, AttributeValue>> maps(AttributeValue value) {
        var result = new ArrayList<Map<String, AttributeValue>>();
        if (value != null && value.hasL()) for (var element : value.l()) result.add(new HashMap<>(element.m()));
        return result;
    }
    private static Map<String, AttributeValue> find(List<Map<String, AttributeValue>> items, UUID id) {
        return items.stream().filter(p -> id.toString().equals(str(p, "id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado"));
    }
    private static boolean active(Map<String, AttributeValue> item) {
        return item.containsKey("ativo") && Boolean.TRUE.equals(item.get("ativo").bool());
    }
    private static String str(Map<String, AttributeValue> item, String key) {
        var value = item.get(key); return value == null ? null : value.s();
    }
    private static String scalar(Map<String, AttributeValue> item, String key) {
        var value = item.get(key); return value == null ? null : value.s() != null ? value.s() : value.n();
    }
    private static AttributeValue s(String value) { return AttributeValue.fromS(value); }
    private static AttributeValue list(List<AttributeValue> values) { return AttributeValue.builder().l(values).build(); }
    private static AttributeValue listMaps(List<Map<String, AttributeValue>> values) {
        return list(values.stream().map(v -> AttributeValue.builder().m(v).build()).toList());
    }
    private static Map<String, Object> view(Map<String, AttributeValue> item) {
        Map<String, Object> response = new HashMap<>();
        for (var e : item.entrySet()) response.put(e.getKey(), unpack(e.getValue()));
        response.remove("filaStatus"); response.remove("filaOrdem"); response.remove("version");
        return response;
    }
    private static Object unpack(AttributeValue value) {
        if (value.s() != null) return value.s();
        if (value.n() != null) return new BigDecimal(value.n());
        if (value.bool() != null) return value.bool();
        if (value.hasL()) return value.l().stream().map(ExecucaoWorkflowService::unpack).toList();
        if (value.hasM()) {
            Map<String, Object> map = new HashMap<>();
            value.m().forEach((k,v) -> map.put(k, unpack(v)));
            return map;
        }
        return null;
    }
}
