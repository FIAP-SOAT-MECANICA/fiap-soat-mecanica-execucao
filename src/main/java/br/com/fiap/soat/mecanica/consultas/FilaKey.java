package br.com.fiap.soat.mecanica.consultas;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** GSI keys for active orders, matching the monolith's status/date/id order. */
public final class FilaKey {
    public static final String INDEX_NAME = "fila";
    public static final String ACTIVE = "ATIVA";
    private static final DateTimeFormatter UTC_NANOS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private FilaKey() { }

    /** Returns no GSI attributes for terminal/unsupported statuses. */
    public static Map<String, AttributeValue> attributes(String status, String abertaEm, UUID osId) {
        int rank = rank(status);
        if (rank < 0) return Map.of();
        Objects.requireNonNull(osId, "osId");
        String timestamp = UTC_NANOS.format(Instant.parse(Objects.requireNonNull(abertaEm, "abertaEm")));
        return Map.of("filaStatus", AttributeValue.fromS(ACTIVE),
                "filaOrdem", AttributeValue.fromS(rank + "#" + timestamp + "#" + osId));
    }

    public static int rank(String status) {
        return switch (status) {
            case "EM_EXECUCAO" -> 1;
            case "AGUARDANDO_APROVACAO" -> 2;
            case "EM_DIAGNOSTICO" -> 3;
            case "RECEBIDA" -> 4;
            default -> -1;
        };
    }
}
