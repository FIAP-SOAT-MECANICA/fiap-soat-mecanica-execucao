package br.com.fiap.soat.mecanica.estoque;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Entry point for ReservarPecas, IniciarExecucao and LiberarReservaPecas handlers. */
public final class EstoqueService {
    private final EstoqueRepository repository;

    public EstoqueService(EstoqueRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public void reservar(UUID osId, Map<UUID, Integer> pecas) {
        repository.reservar(new ReservaEstoque(osId, pecas));
    }

    public void confirmar(UUID osId) {
        repository.confirmar(Objects.requireNonNull(osId, "osId"));
    }

    public void liberar(UUID osId) {
        repository.liberar(Objects.requireNonNull(osId, "osId"));
    }
}
