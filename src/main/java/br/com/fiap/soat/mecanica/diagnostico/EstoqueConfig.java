package br.com.fiap.soat.mecanica.diagnostico;

import br.com.fiap.soat.mecanica.estoque.DynamoEstoqueRepository;
import br.com.fiap.soat.mecanica.estoque.EstoqueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
class EstoqueConfig {
    @Bean EstoqueService estoqueService(DynamoDbClient dynamo, ObjectMapper mapper,
            @Value("${execucao.estoque.pecas-table}") String pecasTable,
            @Value("${execucao.estoque.reservas-table}") String reservasTable,
            @Value("${execucao.diagnostico.idempotencia-table}") String idempotenciaTable,
            @Value("${execucao.diagnostico.outbox-table}") String outboxTable) {
        return new EstoqueService(new DynamoEstoqueRepository(dynamo, pecasTable, reservasTable,
                idempotenciaTable, outboxTable, mapper));
    }
}
