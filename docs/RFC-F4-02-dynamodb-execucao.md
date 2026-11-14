# RFC-F4-02 — Modelagem DynamoDB da Execução

Status: implementação local; revisão de integração com o OS pendente.

## Contexto e decisão

Execução é dona dos diagnósticos, prestações, peças e reservas. Cada OS é um agregado de escrita; uma prestação e suas alocações ficam no item da OS para que uma mudança de diagnóstico use uma gravação condicional por versão. Peças e reservas ficam em tabelas separadas porque várias OS podem disputar a mesma peça. O serviço usa o AWS SDK v2 de baixo nível, `DynamoDbClient`, e `TransactWriteItems`; o plano original citava o Enhanced Client, mas não há esse adapter nesta implementação.

| Tabela | Chave | Conteúdo e acesso |
| --- | --- | --- |
| `execucao-ordens` | `osId` (S) | Diagnóstico, estado, prestações aninhadas, versão; consulta direta por OS. |
| `execucao-pecas` | `pecaId` (S) | Cadastro, `valorUnitario`, `qtdEstoque`, `qtdDisponivel`, `qtdReservada`; operações condicionais por peça. |
| `execucao-reservas` | `osId` (S), `pecaId` (S) | Quantidade e `cycleId` por OS e peça; item sentinela `pecaId=__CYCLE__` guarda o ciclo atual e serializa novas reservas. |
| `execucao-idempotencia` | `messageId` (S) | Deduplicação de comandos e tombstones `cycle:<osId>:<cycleId>`; `expiresAt` é TTL apenas para marcadores de diagnóstico. Marcadores de estoque e tombstones não expiram. |
| `execucao-outbox` | `eventId` (S) | Envelope de evento pendente para SNS. |

`execucao-ordens` possui o GSI esparso `fila` com `filaStatus` como partição e `filaOrdem` como ordenação, projeção `ALL`. A chave de ordenação codifica prioridade, instante UTC e `osId`, mantendo ordem estável. Itens que saem da fila perdem os atributos do índice. `GET /execucoes/fila` lê esse GSI com cursor; `GET /execucoes/tempo-medio` calcula tempos a partir das ordens concluídas.

## Invariantes e transações

- A abertura de diagnóstico escreve ordem, marca de idempotência e evento `DiagnosticoAberto` em uma transação. Repetir o mesmo `messageId` não cria outra ordem.
- A reserva de peças usa `TransactWriteItems` com condições sobre disponibilidade e registros de reserva. Na mesma transação entram os contadores, a sentinela do ciclo, um tombstone durável do `cycleId`, a marca de `messageId` e a outbox. Uma recusa permanente grava `ReservaPecasRecusada`, sua marca e o tombstone em uma transação; para tentar novamente após reposição de estoque, o OS deve criar outro `cycleId`.
- `IniciarExecucao` confirma o estoque e as reservas, marca a sentinela como confirmada, altera a ordem por versão otimista e grava o marcador do comando e `ExecucaoIniciada` em uma única transação. Uma falha em qualquer ação reverte todas. A liberação devolve a disponibilidade com marca de `messageId` e outbox na mesma transação.
- Alterações de prestação e diagnóstico usam versão otimista do item de ordem. Concluir o diagnóstico e finalizar a última prestação gravam a mudança de estado e o respectivo evento na mesma transação.
- O publicador envia os itens pendentes da outbox ao SNS e então os remove. A janela entre publicação e remoção permite entrega duplicada; consumidores precisam deduplicar por `messageId`.

## Contrato de ciclos com o OS

O produtor de comandos no OS deve incluir `payload.cycleId` como UUID estável em `ReservarPecas`, `LiberarReservaPecas` e `IniciarExecucao`. Uma revisão do orçamento após liberação cria um UUID novo e envia `payload.expectedPreviousCycleId` em `ReservarPecas`, igual ao ciclo liberado (UUID ou `LEGACY` para o primeiro ciclo legado). O serviço compara esse predecessor com a sentinela dentro da transação. Assim, um comando antigo nunca entregue não pode iniciar um ciclo depois de uma revisão mais recente. O `sagaId` do envelope continua identificando a Saga; ele não substitui o `cycleId`. Mensagens sem `cycleId` continuam compatíveis apenas com um primeiro ciclo legado. Após iniciar um ciclo com ID, um comando legado não pode confirmar ou liberar esse estoque. Os eventos de resposta incluem `cycleId` no payload quando o comando o inclui.

O serviço OS ainda não publica esse campo no fluxo integrado; a alteração do produtor, o contrato compartilhado e o teste ponta a ponta continuam pendentes. A implementação local também pressupõe que uma execução com `IniciarExecucao` possui ao menos uma peça reservada; o caso de prestação apenas com mão de obra requer fluxo próprio.

O DynamoDB impõe limite de tamanho por item e por transação. Diagnósticos com muitas prestações ou alocações exigirão medição e eventual divisão do agregado. O lookup de prestação e alocação por ID usa scan paginado na implementação atual; um índice dedicado será necessário com maior volume. O GSI da fila tem consistência eventual, portanto uma mudança de prioridade pode demorar a aparecer na listagem.
Cada peça reservada ocupa duas ações de transação (contador e registro de reserva). Reserva acrescenta sentinela, tombstone, marcador e outbox; início acrescenta ordem, sentinela, marcador e outbox. O limite de 100 ações do DynamoDB permite até 48 peças distintas por ciclo.

## Alternativas e consequências

Uma tabela única simplificaria a gestão de recursos, mas exigiria chaves compostas e índices para padrões de acesso distintos. As cinco tabelas deixam explícitas as fronteiras de concorrência e permitem TTL apenas na idempotência. Transações condicionais custam mais que atualizações isoladas, mas impedem duas reservas da última unidade. O teste de concorrência com LocalStack é a evidência local desse invariante; o comportamento de latência, limites e consistência eventual deve ser validado em DynamoDB real antes de produção.

## Verificação

`./mvnw.cmd verify` executa testes unitários e testes com Testcontainers LocalStack para DynamoDB, SQS e SNS, com gate JaCoCo de 80% das instruções. O roteiro em `scripts/setup-localstack.ps1` e `scripts/demo-localstack.ps1` permite reproduzir abertura duplicada de diagnóstico, reserva e recebimento dos eventos sem credenciais AWS reais. A evidência reproduzível e os limites da validação estão em [evidencias-f6.md](evidencias-f6.md).
