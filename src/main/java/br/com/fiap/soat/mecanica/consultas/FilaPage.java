package br.com.fiap.soat.mecanica.consultas;

import java.util.List;

public record FilaPage(List<FilaItem> itens, String proximoCursor) { }
