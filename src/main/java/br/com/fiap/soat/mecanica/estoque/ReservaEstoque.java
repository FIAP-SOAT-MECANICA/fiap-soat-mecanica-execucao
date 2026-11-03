package br.com.fiap.soat.mecanica.estoque;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A complete, atomic reservation request for one service order. */
public record ReservaEstoque(UUID osId, Map<UUID, Integer> pecas) {
    public ReservaEstoque {
        Objects.requireNonNull(osId, "osId");
        Objects.requireNonNull(pecas, "pecas");
        if (pecas.isEmpty() || pecas.size() > 50) {
            throw new IllegalArgumentException("A reserva deve conter de 1 a 50 pecas");
        }
        pecas = Map.copyOf(pecas);
        if (pecas.keySet().stream().anyMatch(Objects::isNull)
                || pecas.values().stream().anyMatch(qtd -> qtd == null || qtd <= 0)) {
            throw new IllegalArgumentException("Cada peca precisa de ID e quantidade positiva");
        }
    }
}
