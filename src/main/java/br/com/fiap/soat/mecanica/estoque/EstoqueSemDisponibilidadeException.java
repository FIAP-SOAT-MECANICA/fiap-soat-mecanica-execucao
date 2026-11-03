package br.com.fiap.soat.mecanica.estoque;

import java.util.Map;
import java.util.UUID;

/** Permanent stock rejection; the saga may emit ReservaPecasRecusada. */
public final class EstoqueSemDisponibilidadeException extends EstoqueException {
    private final Map<UUID, Integer> faltantes;

    public EstoqueSemDisponibilidadeException(String message, Map<UUID, Integer> faltantes, Throwable cause) {
        super(message, cause);
        this.faltantes = Map.copyOf(faltantes);
    }

    /** Missing quantity per piece, from a consistent read after the rejected transaction. */
    public Map<UUID, Integer> faltantes() {
        return faltantes;
    }
}
