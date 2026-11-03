package br.com.fiap.soat.mecanica.diagnostico;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class OsCatalogoClient implements CatalogoServico {
    private final RestClient client;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    OsCatalogoClient(@Value("${execucao.os.base-url:}") String baseUrl) {
        if (baseUrl.isBlank()) {
            client = null;
        } else {
            var requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(2));
            requestFactory.setReadTimeout(Duration.ofSeconds(2));
            client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        }
    }

    @Override public String nomeAtivo(UUID servicoId) {
        Cached current = cache.get(servicoId);
        if (current != null && current.expiresAt > System.currentTimeMillis()) return current.name;
        if (client == null) throw new IllegalStateException("EXECUCAO_OS_BASE_URL não configurada");
        JsonNode response = client.get().uri("/servicos/{id}", servicoId).retrieve().body(JsonNode.class);
        if (response == null || !"ATIVO".equals(response.path("statusRecursoEnum").asText())
                || response.path("nome").asText().isBlank())
            throw new IllegalArgumentException("Serviço não encontrado ou inativo");
        String name = response.path("nome").asText();
        cache.put(servicoId, new Cached(name, System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()));
        return name;
    }

    private record Cached(String name, long expiresAt) { }
}
