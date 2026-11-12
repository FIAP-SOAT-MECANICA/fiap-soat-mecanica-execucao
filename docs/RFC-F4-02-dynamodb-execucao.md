# RFC-F4-02 — Modelagem DynamoDB da Execução

Status: implementação local; revisão de integração com o OS pendente.

## Contexto e decisão

Execução é dona dos diagnósticos, prestações, peças e reservas. Cada OS é um agregado de escrita; uma prestação e suas alocações ficam no item da OS para que uma mudança de diagnóstico use uma gravação condicional por versão. Peças e reservas ficam em tabelas separadas porque várias OS podem disputar a mesma peça. O serviço usa o AWS SDK v2 de baixo nível, `DynamoDbClient`, e `TransactWriteItems`; o plano original citava o Enhanced Client, mas não há esse adapter nesta implementação.

| Tabela | Chave | Conteúdo e acesso |
| --- | --- | --- |
| `execucao-ordens` | `osId` (S) | Diagnóstico, estado, prestações aninhadas, versão; consulta direta por OS. |
| `execucao-pecas` | `pecaId` (S) | Cadastro, `valorUnitario`, `qtdEstoque`, `qtdDisponivel`, `qtdReservada`; operações condicionais por peça. |
| `execucao-reservas` | `osId` (S), `pecaId` (S) | Quantidade reservada por OS e peça; consulta das reservas da OS para confirmar ou liberar. |
| `execucao-idempotencia` | `messageId` (S) | Deduplicação de `AbrirDiagnostico` e dos comandos de reservar/liberar peças; `expiresAt` é TTL para marcadores de diagnóstico, enquanto os marcadores de estoque duráveis não expiram. |
| `execucao-outbox` | `eventId` (S) | Envelope de evento pendente para SNS. |

`execucao-ordens` possui o GSI esparso `fila` com `filaStatus` como partição e `filaOrdem` como ordenação, projeção `ALL`. A chave de ordenação codifica prioridade, instante UTC e `osId`, mantendo ordem estável. Itens que saem da fila perdem os atributos do índice. `GET /execucoes/fila` lê esse GSI com cursor; `GET /execucoes/tempo-medio` calcula tempos a partir das ordens concluídas.

## Invariantes e transações

- A abertura de diagnóstico escreve ordem, marca de idempotência e evento `DiagnosticoAberto` em uma transação. Repetir o mesmo `messageId` não cria outra ordem.
- A reserva de peças usa `TransactWriteItems` com condições sobre disponibilidade e registros de reserva. Na mesma transação entram mutação dos contadores, marca durável de `messageId` e evento de outbox. Se uma peça estiver indisponível, a operação inteira falha; nenhuma peça é reservada parcialmente. A recusa permanente grava `ReservaPecasRecusada` e sua marca na mesma transação.
- Confirmar a reserva baixa o estoque; liberar devolve a disponibilidade. Liberação, marca de `messageId` e outbox são atômicas. Uma repetição do mesmo comando após a publicação do evento não produz outro efeito.
- Alterações de prestação e diagnóstico usam versão otimista do item de ordem. Concluir o diagnóstico e finalizar a última prestação gravam a mudança de estado e o respectivo evento na mesma transação.
- O publicador envia os itens pendentes da outbox ao SNS e então os remove. A janela entre publicação e remoção permite entrega duplicada; consumidores precisam deduplicar por `messageId`.

Há duas limitações de integração ainda abertas: `IniciarExecucao` confirma o estoque e inicia a ordem em operações separadas, então uma falha entre elas requer recuperação idempotente; e uma nova reserva para a mesma OS depois de `LiberarReservaPecas` (revisão de orçamento) não está garantida pelo desenho atual da chave `osId`/`pecaId`. Esses fluxos precisam de ajuste e teste entre OS e Execução antes de considerar a Saga completa.

O DynamoDB impõe limite de tamanho por item e por transação. Diagnósticos com muitas prestações ou alocações exigirão medição e eventual divisão do agregado. O lookup de prestação e alocação por ID usa scan paginado na implementação atual; um índice dedicado será necessário com maior volume. O GSI da fila tem consistência eventual, portanto uma mudança de prioridade pode demorar a aparecer na listagem.
Cada peça reservada ocupa duas ações de transação (contador e registro de reserva); marca e outbox ocupam mais duas. O limite de 100 ações do DynamoDB deixa, portanto, no máximo 49 peças distintas em uma reserva.

## Alternativas e consequências

Uma tabela única simplificaria a gestão de recursos, mas exigiria chaves compostas e índices para padrões de acesso distintos. As cinco tabelas deixam explícitas as fronteiras de concorrência e permitem TTL apenas na idempotência. Transações condicionais custam mais que atualizações isoladas, mas impedem duas reservas da última unidade. O teste de concorrência com LocalStack é a evidência local desse invariante; o comportamento de latência, limites e consistência eventual deve ser validado em DynamoDB real antes de produção.

## Verificação

`./mvnw.cmd verify` executa testes unitários e testes com Testcontainers LocalStack para DynamoDB, SQS e SNS, com gate JaCoCo de 80% das instruções. O roteiro em `scripts/setup-localstack.ps1` e `scripts/demo-localstack.ps1` permite reproduzir abertura duplicada de diagnóstico, reserva e recebimento dos eventos sem credenciais AWS reais. A evidência reproduzível e os limites da validação estão em [evidencias-f6.md](evidencias-f6.md).
