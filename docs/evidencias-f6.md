# Evidências F6 — Execução

Este arquivo descreve como gerar evidência local; resultados de AWS, EKS, SonarCloud e GitHub precisam ser capturados no ambiente de entrega.

## Resultado observado em 11/11/2026 (ambiente local)

- `./mvnw.cmd -q verify` passou nesta revisão: **26 testes**, zero falhas, erros ou skips; JaCoCo cobriu **4.057 de 4.962 instruções (81,76%)**, com o gate de 80% aprovado.
- O roteiro completo passou com LocalStack 4.9.2 e catálogo OS simulado em `localhost:8099`: `PASS osId=443ac62a-5c59-4596-b6d3-e9b7b554d2df pecaId=2c610dd5-8e6c-4391-b809-a78b6184ea76`. Foram conferidos prestação, alocação, conclusão do diagnóstico, reserva, recusa, início e finalização, além de liberação em outra OS.
- A execução do roteiro revelou que `GET /execucoes/{osId}` falhava após a transição de estado. O modelo e o repositório foram corrigidos, e um teste de regressão cobre consultas nos estados `EM_EXECUCAO` e `FINALIZADA`.

A conclusão da execução foi demonstrada no serviço isolado. O comando `IniciarExecucao` ainda confirma estoque e altera a ordem em operações separadas; esta evidência não comprova atomicidade desse pivô nem o fluxo entre microsserviços reais.

## Resultado observado em 10/11/2026 (ambiente local)

- `./mvnw.cmd verify` passou com Docker Desktop e LocalStack ativos após a integração do cadastro de peças (base integrada `acd7387`): 25 testes, zero falhas, erros ou skips; JaCoCo 4.036 instruções cobertas e 910 não cobertas, **81,60%**, com `jacoco:check` aprovado. O HTML é gerado em `target/site/jacoco/index.html` no checkout usado para a verificação. O CI deve repetir a verificação no SHA final.
- `./scripts/demo-localstack.ps1` passou com cadastro de peça por REST, comando de diagnóstico duplicado, reserva da última unidade, recusa da segunda OS, liberação e eventos SNS. A saída incluiu `PASS osId=ed1c3a45-c147-425f-bdab-8ce4643e39b2 pecaId=48238648-c968-4e2d-b4db-16367731255c`.
- `GET /v3/api-docs` respondeu com `/pecas`, `/pecas/{id}` e os caminhos de diagnóstico/consultas. `GET /swagger-ui.html` redirecionou à interface Swagger (HTTP 302).
- Os scripts da demonstração foram executados em PowerShell 7.6.9 no Windows. A sintaxe de argumentos JSON da AWS CLI em Windows PowerShell 5.1 não foi verificada.

Esses resultados são locais; não são evidência de deploy em EKS nem do Quality Gate do SonarCloud.

## Testes e cobertura

1. Com Docker Desktop ativo, execute `./mvnw.cmd verify` (Windows) ou `./mvnw verify` (Linux/macOS).
2. Guarde a saída do Maven mostrando testes, `jacoco:check` e o resultado final. O relatório HTML fica em `target/site/jacoco/index.html`; o XML fica em `target/site/jacoco/jacoco.xml`.
3. Os testes `DiagnosticoLocalStackTest` e `ExecucaoWorkflowLocalStackTest` exercitam DynamoDB, SQS e SNS no LocalStack. `DynamoEstoqueRepositoryTest` e `EstoqueCommandHandlerTest` cobrem a reserva concorrente e a idempotência da outbox.

O gate de 80% está no `pom.xml`. Não substitua sua evidência por percentuais calculados apenas em um pacote. Para o PDF final, capture o resultado do CI e do Quality Gate SonarCloud, com URL e SHA do mesmo commit.

## Demonstração local

1. Na raiz do repositório, em PowerShell, execute `. .\scripts\setup-localstack.ps1` para iniciar LocalStack e manter as variáveis de ambiente na sessão.
2. Em outra sessão, execute `pwsh -File .\scripts\mock-os-catalogo.ps1`. Ele responde localmente a `GET /servicos/{id}` para a inclusão da prestação; encerre com Ctrl+C após a demonstração.
3. Na sessão do setup, execute `.\mvnw.cmd spring-boot:run` e aguarde `GET http://localhost:8080/actuator/health` responder `UP`.
4. Em uma terceira sessão, execute `.\scripts\demo-localstack.ps1`. O script cria IDs novos, envia duas cópias de `AbrirDiagnostico`, inclui prestação com o catálogo local, cadastra e aloca peça, conclui diagnóstico, reserva a última unidade, verifica recusa em outra OS, inicia e finaliza a execução, e libera uma reserva separada. Também confere eventos na fila assinante de SNS.
5. Guarde a linha `PASS` com `osId` e `pecaId`, a resposta `GET /execucoes/{osId}`, a marca de idempotência consultada por `messageId` e o estado do estoque. O script falha caso algum desses passos não aconteça.

O roteiro utiliza apenas credenciais de teste e endpoint `localhost:4566`; não configura nem modifica uma conta AWS. O catálogo OS é simulado por um servidor HTTP restrito a localhost. Ele demonstra o serviço Execução isolado com uma fila de respostas simulando o OS, não a Saga completa entre os quatro serviços nem a integração real com o catálogo OS.

## Contrato e arquitetura

- Swagger UI: `http://localhost:8080/swagger-ui.html`; OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- Arquitetura do serviço e interfaces: [README](../README.md).
- Modelagem e invariantes DynamoDB: [RFC-F4-02](RFC-F4-02-dynamodb-execucao.md).
- Plano de referência da Fase 4: [Planejamento da Fase 4](https://gist.github.com/lenilsonf/c8fa28726f348a97c178e870665cef4f).
