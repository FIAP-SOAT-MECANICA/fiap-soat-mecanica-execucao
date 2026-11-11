package br.com.fiap.soat.mecanica.estoque;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Repository
public class DynamoPecaRepository implements PecaRepository {
    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoPecaRepository(DynamoDbClient dynamo,
            @Value("${execucao.estoque.pecas-table}") String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override public Peca criar(Peca peca) {
        try {
            dynamo.putItem(PutItemRequest.builder().tableName(table).item(Map.of(
                    "pecaId", s(peca.pecaId().toString()), "nome", s(peca.nome()),
                    "descricao", s(peca.descricao()), "valorUnitario", s(peca.valorUnitario().toPlainString()),
                    "qtdEstoque", n(peca.qtdEstoque()), "qtdDisponivel", n(peca.qtdEstoque()),
                    "qtdReservada", n(0), "version", n(0), "ativo", AttributeValue.fromBool(true)))
                    .conditionExpression("attribute_not_exists(pecaId)").build());
            return peca;
        } catch (ConditionalCheckFailedException e) {
            throw new EstoqueException("Peça já cadastrada", e);
        }
    }

    @Override public Optional<Peca> buscar(UUID id) {
        var item = dynamo.getItem(GetItemRequest.builder().tableName(table).key(key(id))
                .consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(from(item));
    }

    @Override public List<Peca> listar() {
        var pieces = new ArrayList<Peca>();
        for (var page : dynamo.scanPaginator(ScanRequest.builder().tableName(table).build()))
            for (var item : page.items()) pieces.add(from(item));
        return pieces;
    }

    @Override public Peca atualizar(Peca peca) {
        var old = dynamo.getItem(GetItemRequest.builder().tableName(table).key(key(peca.pecaId()))
                .consistentRead(true).build()).item();
        if (old == null || old.isEmpty()) throw new IllegalArgumentException("Peça não encontrada");
        int difference = Math.subtractExact(peca.qtdEstoque(), Integer.parseInt(old.get("qtdEstoque").n()));
        long version = old.containsKey("version") ? Long.parseLong(old.get("version").n()) : 0;
        var values = new HashMap<String, AttributeValue>();
        values.put(":nome", s(peca.nome())); values.put(":descricao", s(peca.descricao()));
        values.put(":valor", s(peca.valorUnitario().toPlainString()));
        values.put(":delta", n(difference)); values.put(":minimum", n(-difference));
        values.put(":version", AttributeValue.fromN(Long.toString(version)));
        values.put(":next", AttributeValue.fromN(Long.toString(version + 1)));
        values.put(":oldStock", old.get("qtdEstoque"));
        values.put(":active", AttributeValue.fromBool(true));
        try {
            var result = dynamo.updateItem(UpdateItemRequest.builder().tableName(table).key(key(peca.pecaId()))
                    .updateExpression("SET nome = :nome, descricao = :descricao, valorUnitario = :valor, "
                            + "version = :next ADD qtdEstoque :delta, qtdDisponivel :delta")
                    .conditionExpression("attribute_exists(pecaId) AND "
                            + "(ativo = :active OR attribute_not_exists(ativo)) AND "
                            + "(version = :version OR attribute_not_exists(version)) AND "
                            + "qtdEstoque = :oldStock AND qtdDisponivel >= :minimum")
                    .expressionAttributeValues(values).returnValues("ALL_NEW").build());
            return from(result.attributes());
        } catch (ConditionalCheckFailedException e) {
            throw new EstoqueException("Peça alterada simultaneamente ou estoque reservado", e);
        }
    }

    private static Peca from(Map<String, AttributeValue> item) {
        return new Peca(UUID.fromString(item.get("pecaId").s()), scalar(item, "nome", ""),
                scalar(item, "descricao", ""), new BigDecimal(scalar(item, "valorUnitario", "0")),
                Integer.parseInt(item.get("qtdEstoque").n()), Integer.parseInt(item.get("qtdDisponivel").n()),
                Integer.parseInt(item.get("qtdReservada").n()));
    }
    private static String scalar(Map<String, AttributeValue> item, String name, String fallback) {
        var value = item.get(name);
        return value == null ? fallback : value.s() != null ? value.s() : value.n();
    }
    private static Map<String, AttributeValue> key(UUID id) { return Map.of("pecaId", s(id.toString())); }
    private static AttributeValue s(String value) { return AttributeValue.fromS(value); }
    private static AttributeValue n(int value) { return AttributeValue.fromN(Integer.toString(value)); }
}
