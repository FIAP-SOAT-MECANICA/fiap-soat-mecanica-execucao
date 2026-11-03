package br.com.fiap.soat.mecanica.diagnostico;

import java.time.Instant;
import java.util.UUID;

public record OrdemDiagnostico(UUID osId, UUID sagaId, UUID veiculoId, String placa, Instant abertaEm,
                               String status) {
    public OrdemDiagnostico {
        if (osId == null || sagaId == null || veiculoId == null || abertaEm == null || placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("Dados obrigatórios do diagnóstico ausentes");
        }
        if (!"EM_DIAGNOSTICO".equals(status)) {
            throw new IllegalArgumentException("Estado inicial inválido");
        }
    }
}
