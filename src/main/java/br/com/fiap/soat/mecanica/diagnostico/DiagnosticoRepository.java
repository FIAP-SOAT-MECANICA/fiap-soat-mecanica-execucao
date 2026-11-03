package br.com.fiap.soat.mecanica.diagnostico;

import java.util.Optional;
import java.util.UUID;

public interface DiagnosticoRepository {
    /** Saves the order, processed command and outbox event atomically. Returns false on redelivery. */
    boolean abrirSeAusente(OrdemDiagnostico ordem, UUID commandId, MessageEnvelope event);
    Optional<OrdemDiagnostico> buscar(UUID osId);
}
