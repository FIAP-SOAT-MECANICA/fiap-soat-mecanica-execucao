# Evidências F6 — Execução

Este arquivo descreve como gerar evidência local; resultados de AWS, EKS, SonarCloud e GitHub precisam ser capturados no ambiente de entrega.

## Testes e cobertura

1. Com Docker Desktop ativo, execute `./mvnw.cmd verify` (Windows) ou `./mvnw verify` (Linux/macOS).
2. Guarde a saída do Maven mostrando testes, `jacoco:check` e o resultado final. O relatório HTML fica em `target/site/jacoco/index.html`; o XML fica em `target/site/jacoco/jacoco.xml`.
3. Os testes `DiagnosticoLocalStackTest` e `ExecucaoWorkflowLocalStackTest` exercitam DynamoDB, SQS e SNS no LocalStack. Registre também o teste de concorrência de reservas da última peça quando a mudança de estoque estiver integrada.

O gate de 80% está no `pom.xml`. Não substitua sua evidência por percentuais calculados apenas em um pacote. Para o PDF final, capture o resultado do CI e do Quality Gate SonarCloud, com URL e SHA do mesmo commit.

## Demonstração local

1. Na raiz do repositório, em PowerShell, execute `. .\scripts\setup-localstack.ps1` para iniciar LocalStack e manter as variáveis de ambiente na sessão.
2. Na mesma sessão, execute `.\mvnw.cmd spring-boot:run` e aguarde `GET http://localhost:8080/actuator/health` responder `UP`.
3. Em outra sessão, execute `.\scripts\demo-localstack.ps1`. O script cria IDs novos, envia duas cópias de `AbrirDiagnostico`, reserva a última unidade de uma peça, verifica a recusa para uma segunda OS, libera a reserva e recebe os eventos na fila assinante de SNS.
4. Guarde a linha `PASS` com `osId` e `pecaId`, a resposta `GET /execucoes/{osId}`, a contagem de idempotência e o estado do estoque. O script falha caso algum desses passos não aconteça.

O roteiro utiliza apenas credenciais de teste e endpoint `localhost:4566`; não configura nem modifica uma conta AWS. Ele demonstra um serviço isolado com uma fila de respostas simulando o OS, não a Saga completa entre os quatro serviços. A chamada REST ao catálogo do OS para incluir uma prestação requer o OS disponível e `EXECUCAO_OS_BASE_URL` configurada.

## Contrato e arquitetura

- Swagger UI: `http://localhost:8080/swagger-ui.html`; OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- Arquitetura do serviço e interfaces: [README](../README.md).
- Modelagem e invariantes DynamoDB: [RFC-F4-02](RFC-F4-02-dynamodb-execucao.md).
- Plano de referência da Fase 4: [Planejamento da Fase 4](https://gist.github.com/lenilsonf/c8fa28726f348a97c178e870665cef4f).
