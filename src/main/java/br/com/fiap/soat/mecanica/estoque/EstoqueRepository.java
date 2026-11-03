package br.com.fiap.soat.mecanica.estoque;

import java.util.UUID;

public interface EstoqueRepository {
    void reservar(ReservaEstoque reserva);
    void confirmar(UUID osId);
    void liberar(UUID osId);
}
