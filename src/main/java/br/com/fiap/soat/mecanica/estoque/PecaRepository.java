package br.com.fiap.soat.mecanica.estoque;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PecaRepository {
    Peca criar(Peca peca);
    Optional<Peca> buscar(UUID id);
    List<Peca> listar();
    Peca atualizar(Peca peca);
}
