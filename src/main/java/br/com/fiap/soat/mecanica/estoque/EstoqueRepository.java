package br.com.fiap.soat.mecanica.estoque;

import java.util.UUID;
import br.com.fiap.soat.mecanica.diagnostico.MessageEnvelope;

public interface EstoqueRepository {
    void reservar(ReservaEstoque reserva);
    void confirmar(UUID osId);
    void liberar(UUID osId);
    void reservar(ReservaEstoque reserva, MessageEnvelope event);
    void liberar(UUID osId, MessageEnvelope event);
    boolean jaProcessado(UUID messageId);
    void registrarResultado(MessageEnvelope event);
}
