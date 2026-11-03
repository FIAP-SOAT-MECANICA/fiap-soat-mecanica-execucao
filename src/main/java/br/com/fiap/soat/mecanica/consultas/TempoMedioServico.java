package br.com.fiap.soat.mecanica.consultas;

import java.util.UUID;

public record TempoMedioServico(UUID servicoId, String nomeServico,
        double tempoMedioSegundos, long amostras) { }
