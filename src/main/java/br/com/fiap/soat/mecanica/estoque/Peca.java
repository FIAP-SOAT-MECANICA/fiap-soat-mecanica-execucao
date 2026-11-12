package br.com.fiap.soat.mecanica.estoque;

import java.math.BigDecimal;
import java.util.UUID;

public record Peca(UUID pecaId, String nome, String descricao, BigDecimal valorUnitario,
                   int qtdEstoque, int qtdDisponivel, int qtdReservada) {
}
