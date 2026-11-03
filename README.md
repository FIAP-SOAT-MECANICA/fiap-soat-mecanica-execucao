# Execução

Serviço de diagnóstico e estoque da mecânica. Consome `AbrirDiagnostico` de SQS, cria a execução em DynamoDB e grava `DiagnosticoAberto` na outbox transacional para publicar em SNS. O processamento repetido da mesma OS e Saga não cria outra execução. Também consome `ReservarPecas`, `LiberarReservaPecas` e `IniciarExecucao`, emitindo as respostas correspondentes. Uma recusa permanente de reserva emite `ReservaPecasRecusada`; conflitos transitórios permanecem na fila para nova tentativa.

## Executar

Java 21 e Maven Wrapper: `./mvnw.cmd test` no Windows ou `./mvnw test` no Linux. Configure `AWS_REGION`, `EXECUCAO_ORDENS_TABLE`, `EXECUCAO_IDEMPOTENCIA_TABLE`, `EXECUCAO_OUTBOX_TABLE`, `EXECUCAO_PECAS_TABLE`, `EXECUCAO_RESERVAS_TABLE`, `EXECUCAO_COMMAND_QUEUE_URL` e `EXECUCAO_EVENT_TOPIC_ARN`. Para LocalStack, defina `AWS_ENDPOINT` e credenciais de teste. Para construir a imagem, rode `./mvnw.cmd package` e `docker build -t mecanica-execucao .`.

O endpoint `GET /actuator/health` verifica a aplicação; `GET /execucoes/{osId}` consulta o diagnóstico. A fila deve ser padrão, ter DLQ e receber mensagens com entrega bruta. O tópico deve ter assinatura para a fila de respostas da Saga no serviço OS.

## Tabelas DynamoDB

- `execucao-ordens`: chave de partição `osId` (String)
- `execucao-idempotencia`: chave de partição `messageId` (String), TTL no atributo `expiresAt`
- `execucao-outbox`: chave de partição `eventId` (String)
- `execucao-pecas`: chave de partição `pecaId` (String), com `qtdEstoque`, `qtdDisponivel` e `qtdReservada`
- `execucao-reservas`: chave de partição `osId` e chave de ordenação `pecaId` (String)

As três tabelas de diagnóstico participam da mesma `TransactWriteItems`. A reserva de peças também usa `TransactWriteItems` condicional. Seu evento é gravado após a mutação; se a gravação do evento falhar, a mensagem SQS será repetida e a operação de estoque idempotente permitirá completar a outbox. O publicador varre a outbox pendente; após a publicação, remove o item. Em caso de falha entre publicação e remoção, pode publicar novamente, portanto os consumidores também precisam deduplicar por `messageId`.
