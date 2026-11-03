package br.com.fiap.soat.mecanica.consultas;

import java.time.Instant;
import java.util.UUID;

public record FilaItem(UUID osId, String status, String placa, Instant abertaEm) { }
