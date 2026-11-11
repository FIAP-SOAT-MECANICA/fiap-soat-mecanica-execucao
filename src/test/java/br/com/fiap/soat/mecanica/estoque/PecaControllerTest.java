package br.com.fiap.soat.mecanica.estoque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PecaControllerTest {
    private final PecaRepository repository = mock(PecaRepository.class);
    private final PecaController controller = new PecaController(repository);
    private final PecaController.PecaRequest request = new PecaController.PecaRequest(
            " Filtro ", " Óleo ", new BigDecimal("12.50"), 2);

    @Test void criaBuscaListaEAtualizaPeca() {
        when(repository.criar(any())).thenAnswer(i -> i.getArgument(0));
        var response = controller.criar(request);
        assertEquals(201, response.getStatusCode().value());
        Peca created = response.getBody();
        assertEquals("Filtro", created.nome());
        assertEquals("Óleo", created.descricao());
        assertEquals("/pecas/" + created.pecaId(), response.getHeaders().getLocation().toString());
        when(repository.buscar(created.pecaId())).thenReturn(Optional.of(created));
        when(repository.listar()).thenReturn(List.of(created));
        when(repository.atualizar(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(created, controller.buscar(created.pecaId()));
        assertEquals(List.of(created), controller.listar());
        assertEquals(created, controller.atualizar(created.pecaId(), request));
        verify(repository).atualizar(created);
    }

    @Test void rejeitaEntradaInvalidaEPecaAusente() {
        assertThrows(IllegalArgumentException.class, () -> controller.criar(
                new PecaController.PecaRequest(" ", "Óleo", BigDecimal.ONE, 1)));
        assertThrows(IllegalArgumentException.class, () -> controller.criar(
                new PecaController.PecaRequest("Filtro", "Óleo", BigDecimal.ONE, -1)));
        assertThrows(IllegalArgumentException.class, () -> controller.criar(
                new PecaController.PecaRequest("Filtro", "Óleo", BigDecimal.valueOf(-1), 1)));
        UUID missing = UUID.randomUUID();
        assertEquals("Peça não encontrada: " + missing,
                assertThrows(RuntimeException.class, () -> controller.buscar(missing)).getMessage());
    }
}
