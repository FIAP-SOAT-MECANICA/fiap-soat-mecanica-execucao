package br.com.fiap.soat.mecanica.consultas;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

@Repository
public final class DynamoConsultaExecucaoRepository implements ConsultaExecucaoRepository {
    private final DynamoDbClient dynamo;
    private final String ordensTable;

    public DynamoConsultaExecucaoRepository(DynamoDbClient dynamo,
            @Value("${execucao.diagnostico.ordens-table}") String ordensTable) {
        this.dynamo = dynamo;
        this.ordensTable = ordensTable;
    }

    @Override
    public FilaPage listarFila(int limite, String cursor) {
        var request = QueryRequest.builder().tableName(ordensTable).indexName(FilaKey.INDEX_NAME)
                .keyConditionExpression("filaStatus = :ativa")
                .expressionAttributeValues(Map.of(":ativa", AttributeValue.fromS(FilaKey.ACTIVE)))
                .scanIndexForward(true).limit(limite);
        if (cursor != null && !cursor.isBlank()) {
            request.exclusiveStartKey(decodeCursor(cursor));
        }
        var response = dynamo.query(request.build());
        List<FilaItem> items = response.items().stream().map(DynamoConsultaExecucaoRepository::toFilaItem)
                .toList();
        Map<String, AttributeValue> next = response.lastEvaluatedKey();
        return new FilaPage(items, next == null || next.isEmpty() ? null : encodeCursor(next));
    }

    @Override
    public List<TempoMedioServico> temposMedios() {
        var request = ScanRequest.builder().tableName(ordensTable)
                .projectionExpression("prestacoes").build();
        Map<UUID, Stats> stats = new HashMap<>();
        for (var page : dynamo.scanPaginator(request)) {
            for (var order : page.items()) {
                AttributeValue prestations = order.get("prestacoes");
                if (prestations == null || !prestations.hasL()) continue;
                for (var prestation : prestations.l()) {
                    if (!prestation.hasM()) continue;
                    Map<String, AttributeValue> fields = prestation.m();
                    if (!hasString(fields, "servicoId") || !hasString(fields, "dataInicio")
                            || !hasString(fields, "dataFim")) continue;
                    Instant start = Instant.parse(fields.get("dataInicio").s());
                    Instant end = Instant.parse(fields.get("dataFim").s());
                    if (!end.isAfter(start)) continue;
                    UUID serviceId = UUID.fromString(fields.get("servicoId").s());
                    String name = hasString(fields, "nomeServico")
                            ? fields.get("nomeServico").s() : serviceId.toString();
                    stats.computeIfAbsent(serviceId, ignored -> new Stats(name))
                            .add(Duration.between(start, end).toMillis() / 1000.0);
                }
            }
        }
        return stats.entrySet().stream()
                .map(entry -> new TempoMedioServico(entry.getKey(), entry.getValue().name,
                        entry.getValue().sum / entry.getValue().count, entry.getValue().count))
                .sorted(Comparator.comparing(TempoMedioServico::nomeServico)
                        .thenComparing(item -> item.servicoId().toString()))
                .toList();
    }

    private static boolean hasString(Map<String, AttributeValue> fields, String key) {
        AttributeValue value = fields.get(key);
        return value != null && value.s() != null && !value.s().isBlank();
    }

    private static FilaItem toFilaItem(Map<String, AttributeValue> item) {
        return new FilaItem(UUID.fromString(item.get("osId").s()), item.get("status").s(),
                item.get("placa").s(), Instant.parse(item.get("abertaEm").s()));
    }

    private static String encodeCursor(Map<String, AttributeValue> key) {
        String value = key.get("osId").s() + "|" + key.get("filaOrdem").s();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] pieces = value.split("\\|", 2);
            if (pieces.length != 2 || pieces[1].isBlank()) throw new IllegalArgumentException();
            return Map.of("osId", AttributeValue.fromS(UUID.fromString(pieces[0]).toString()),
                    "filaStatus", AttributeValue.fromS(FilaKey.ACTIVE),
                    "filaOrdem", AttributeValue.fromS(pieces[1]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor invalido", invalid);
        }
    }

    private static final class Stats {
        private final String name;
        private double sum;
        private long count;

        private Stats(String name) { this.name = name; }
        private void add(double seconds) { sum += seconds; count++; }
    }
}
