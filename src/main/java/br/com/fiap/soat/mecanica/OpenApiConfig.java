package br.com.fiap.soat.mecanica;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Mecânica — Execução", version = "1.0.0",
                description = "Diagnóstico, prestações, fila de execução e estoque de peças."),
        tags = {
                @Tag(name = "Execução", description = "Diagnóstico e prestações da ordem de serviço"),
                @Tag(name = "Consultas", description = "Fila priorizada e tempos médios"),
                @Tag(name = "Peças", description = "Catálogo e estoque de peças")
        })
public class OpenApiConfig { }
