package br.com.fiap.soat.mecanica.estoque;

/** Retry the SQS command; concurrent transaction conflicts are not stock refusal. */
public final class EstoqueConflitoTransitorioException extends EstoqueException {
    public EstoqueConflitoTransitorioException(String message, Throwable cause) {
        super(message, cause);
    }
}
