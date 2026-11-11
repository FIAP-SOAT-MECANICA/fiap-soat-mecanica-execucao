param([string] $BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$env:AWS_ACCESS_KEY_ID = 'test'
$env:AWS_SECRET_ACCESS_KEY = 'test'
$env:AWS_SESSION_TOKEN = ''
$env:AWS_DEFAULT_REGION = 'us-east-1'
$endpoint = 'http://localhost:4566'

function Invoke-LocalAws {
    param([string[]] $Arguments)
    $output = & aws --endpoint-url $endpoint @Arguments
    if ($LASTEXITCODE -ne 0) { throw "LocalStack AWS CLI failed: $($Arguments -join ' ')" }
    return $output
}

$queue = (Invoke-LocalAws @('sqs', 'get-queue-url', '--queue-name', 'mecanica-execucao-comandos',
    '--query', 'QueueUrl', '--output', 'text')).Trim()
$responseQueue = (Invoke-LocalAws @('sqs', 'get-queue-url', '--queue-name', 'os-saga-respostas-demo',
    '--query', 'QueueUrl', '--output', 'text')).Trim()
Invoke-RestMethod "$BaseUrl/actuator/health" | Out-Null
try { Invoke-RestMethod 'http://localhost:8099/health' | Out-Null }
catch { throw 'Inicie o catálogo local: pwsh -File .\scripts\mock-os-catalogo.ps1. Configure EXECUCAO_OS_BASE_URL=http://localhost:8099 antes de iniciar a aplicação.' }

$osId = [guid]::NewGuid().ToString()
$sagaId = [guid]::NewGuid().ToString()
$commandId = [guid]::NewGuid().ToString()
$pecaId = $null
$opened = @{
    messageId = $commandId; type = 'AbrirDiagnostico'; version = 1
    sagaId = $sagaId; osId = $osId; correlationId = "demo-$osId"
    occurredAt = [DateTime]::UtcNow.ToString('o')
    payload = @{ osId = $osId; veiculoId = [guid]::NewGuid().ToString(); placa = 'ABC1D23' }
} | ConvertTo-Json -Depth 8 -Compress
foreach ($repeat in 1..2) {
    Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $opened) | Out-Null
}

$order = $null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    try { $order = Invoke-RestMethod "$BaseUrl/execucoes/$osId"; break }
    catch { Start-Sleep -Seconds 1 }
}
if (-not $order -or $order.status -ne 'EM_DIAGNOSTICO') {
    throw 'AbrirDiagnostico did not create an order in EM_DIAGNOSTICO.'
}

$servicoId = [guid]::NewGuid().ToString()
$prestacaoBody = @{ servicoId = $servicoId; precoMdo = 80.00 } | ConvertTo-Json -Compress
$withPrestacao = Invoke-RestMethod "$BaseUrl/execucoes/$osId/prestacoes" -Method Post `
    -ContentType 'application/json' -Body $prestacaoBody
$prestacaoId = $withPrestacao.prestacoes[0].id
if (-not $prestacaoId -or $withPrestacao.prestacoes[0].nomeServico -ne 'Troca de filtro (mock)') {
    throw 'POST prestação não consultou o catálogo OS local.'
}

$pieceBody = @{ nome = 'Filtro de óleo'; descricao = 'Peça de demonstração'
    valorUnitario = 29.90; qtdEstoque = 1 } | ConvertTo-Json -Compress
$piece = Invoke-RestMethod "$BaseUrl/pecas" -Method Post -ContentType 'application/json' -Body $pieceBody
$pecaId = $piece.pecaId
if (-not $pecaId -or $piece.qtdDisponivel -ne 1 -or $piece.qtdReservada -ne 0) {
    throw 'POST /pecas did not create one available piece.'
}
$alocacaoBody = @{ pecaId = $pecaId; qtd = 1 } | ConvertTo-Json -Compress
$withAllocation = Invoke-RestMethod "$BaseUrl/execucoes/$osId/prestacoes/$prestacaoId/pecas" `
    -Method Post -ContentType 'application/json' -Body $alocacaoBody
if ($withAllocation.prestacoes[0].pecas[0].pecaId -ne $pecaId) {
    throw 'A peça não foi alocada à prestação.'
}
$diagnosed = Invoke-RestMethod "$BaseUrl/execucoes/$osId/diagnostico/concluir" -Method Patch
if ($diagnosed.status -ne 'AGUARDANDO_APROVACAO') {
    throw 'Concluir diagnóstico não alterou o status para AGUARDANDO_APROVACAO.'
}
$reserveId = [guid]::NewGuid().ToString()
$reserve = @{
    messageId = $reserveId; type = 'ReservarPecas'; version = 1
    sagaId = $sagaId; osId = $osId; correlationId = "demo-$osId"
    causationId = $commandId; occurredAt = [DateTime]::UtcNow.ToString('o')
    payload = @{ osId = $osId; pecas = @(@{ pecaId = $pecaId; qtd = 1 }) }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $reserve) | Out-Null

$stock = $null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $key = @{ pecaId = @{ S = $pecaId } } | ConvertTo-Json -Depth 4 -Compress
    $stock = Invoke-LocalAws @('dynamodb', 'get-item', '--table-name', 'execucao-pecas', '--key', $key, '--output', 'json') |
        ConvertFrom-Json
    if ($stock.Item.qtdDisponivel.N -eq '0' -and $stock.Item.qtdReservada.N -eq '1') { break }
    Start-Sleep -Seconds 1
}
if ($stock.Item.qtdDisponivel.N -ne '0' -or $stock.Item.qtdReservada.N -ne '1') {
    throw 'ReservarPecas did not atomically update stock.'
}
$pieceAfterReserve = Invoke-RestMethod "$BaseUrl/pecas/$pecaId"
if ($pieceAfterReserve.qtdDisponivel -ne 0 -or $pieceAfterReserve.qtdReservada -ne 1) {
    throw 'GET /pecas/{id} disagrees with the reserved stock.'
}

$eventTypes = [System.Collections.Generic.HashSet[string]]::new()
for ($attempt = 0; $attempt -lt 12 -and $eventTypes.Count -lt 3; $attempt++) {
    $received = Invoke-LocalAws @('sqs', 'receive-message', '--queue-url', $responseQueue,
        '--wait-time-seconds', '2', '--max-number-of-messages', '10', '--output', 'json') | ConvertFrom-Json
    foreach ($message in $received.Messages) {
        $event = $message.Body | ConvertFrom-Json
        if ($event.osId -eq $osId) { [void] $eventTypes.Add($event.type) }
        Invoke-LocalAws @('sqs', 'delete-message', '--queue-url', $responseQueue,
            '--receipt-handle', $message.ReceiptHandle) | Out-Null
    }
}
if (-not $eventTypes.Contains('DiagnosticoAberto') -or
        -not $eventTypes.Contains('DiagnosticoConcluido') -or
        -not $eventTypes.Contains('PecasReservadas')) {
    throw "Eventos esperados de diagnóstico e reserva ausentes: $($eventTypes -join ', ')"
}

# A second OS cannot reserve the last unit.
$otherOsId = [guid]::NewGuid().ToString()
$refusal = @{
    messageId = [guid]::NewGuid().ToString(); type = 'ReservarPecas'; version = 1
    sagaId = [guid]::NewGuid().ToString(); osId = $otherOsId; correlationId = "demo-$otherOsId"
    occurredAt = [DateTime]::UtcNow.ToString('o')
    payload = @{ osId = $otherOsId; pecas = @(@{ pecaId = $pecaId; qtd = 1 }) }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $refusal) | Out-Null
$refused = $false
for ($attempt = 0; $attempt -lt 10 -and -not $refused; $attempt++) {
    $received = Invoke-LocalAws @('sqs', 'receive-message', '--queue-url', $responseQueue,
        '--wait-time-seconds', '2', '--max-number-of-messages', '10', '--output', 'json') | ConvertFrom-Json
    foreach ($message in $received.Messages) {
        $event = $message.Body | ConvertFrom-Json
        if ($event.osId -eq $otherOsId -and $event.type -eq 'ReservaPecasRecusada') { $refused = $true }
        Invoke-LocalAws @('sqs', 'delete-message', '--queue-url', $responseQueue,
            '--receipt-handle', $message.ReceiptHandle) | Out-Null
    }
}
if (-not $refused) { throw 'A second OS was not refused when stock was exhausted.' }

$start = @{
    messageId = [guid]::NewGuid().ToString(); type = 'IniciarExecucao'; version = 1
    sagaId = $sagaId; osId = $osId; correlationId = "demo-$osId"
    occurredAt = [DateTime]::UtcNow.ToString('o'); payload = @{ osId = $osId }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $start) | Out-Null
$started = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $order = Invoke-RestMethod "$BaseUrl/execucoes/$osId"
    if ($order.status -eq 'EM_EXECUCAO') { $started = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $started) { throw 'IniciarExecucao não mudou a OS para EM_EXECUCAO em 20 segundos.' }
$pieceAfterConfirm = Invoke-RestMethod "$BaseUrl/pecas/$pecaId"
if ($pieceAfterConfirm.qtdEstoque -ne 0 -or $pieceAfterConfirm.qtdReservada -ne 0) {
    throw 'IniciarExecucao não confirmou a baixa do estoque.'
}
$finished = Invoke-RestMethod "$BaseUrl/prestacoes/$prestacaoId/finalizar" -Method Patch
if ($finished.status -ne 'FINALIZADA') {
    throw 'Finalizar prestação não mudou a OS para FINALIZADA.'
}

# Separate reservation exercises compensation without releasing the finalized order.
$releasePiece = Invoke-RestMethod "$BaseUrl/pecas" -Method Post -ContentType 'application/json' -Body $pieceBody
$releaseOsId = [guid]::NewGuid().ToString()
$releaseSagaId = [guid]::NewGuid().ToString()
$releaseReserve = @{
    messageId = [guid]::NewGuid().ToString(); type = 'ReservarPecas'; version = 1
    sagaId = $releaseSagaId; osId = $releaseOsId; correlationId = "demo-$releaseOsId"
    occurredAt = [DateTime]::UtcNow.ToString('o')
    payload = @{ osId = $releaseOsId; pecas = @(@{ pecaId = $releasePiece.pecaId; qtd = 1 }) }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $releaseReserve) | Out-Null
$releaseReserved = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $releaseStock = Invoke-RestMethod "$BaseUrl/pecas/$($releasePiece.pecaId)"
    if ($releaseStock.qtdDisponivel -eq 0 -and $releaseStock.qtdReservada -eq 1) {
        $releaseReserved = $true; break
    }
    Start-Sleep -Seconds 1
}
if (-not $releaseReserved) { throw 'Reserva para compensação não apareceu em 20 segundos.' }
$release = @{
    messageId = [guid]::NewGuid().ToString(); type = 'LiberarReservaPecas'; version = 1
    sagaId = $releaseSagaId; osId = $releaseOsId; correlationId = "demo-$releaseOsId"
    occurredAt = [DateTime]::UtcNow.ToString('o'); payload = @{ osId = $releaseOsId }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $release) | Out-Null
$restored = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $releaseStock = Invoke-RestMethod "$BaseUrl/pecas/$($releasePiece.pecaId)"
    if ($releaseStock.qtdDisponivel -eq 1 -and $releaseStock.qtdReservada -eq 0) {
        $restored = $true; break
    }
    Start-Sleep -Seconds 1
}
if (-not $restored) { throw 'LiberarReservaPecas não restaurou disponibilidade em 20 segundos.' }

$lateEvents = [System.Collections.Generic.HashSet[string]]::new()
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    $received = Invoke-LocalAws @('sqs', 'receive-message', '--queue-url', $responseQueue,
        '--wait-time-seconds', '2', '--max-number-of-messages', '10', '--output', 'json') | ConvertFrom-Json
    foreach ($message in $received.Messages) {
        $event = $message.Body | ConvertFrom-Json
        [void] $lateEvents.Add("$($event.osId):$($event.type)")
        Invoke-LocalAws @('sqs', 'delete-message', '--queue-url', $responseQueue,
            '--receipt-handle', $message.ReceiptHandle) | Out-Null
    }
    if ($lateEvents.Contains("${osId}:ExecucaoIniciada") -and
            $lateEvents.Contains("${osId}:ExecucaoFinalizada") -and
            $lateEvents.Contains("${releaseOsId}:ReservaPecasLiberada")) { break }
}
foreach ($expected in @("${osId}:ExecucaoIniciada", "${osId}:ExecucaoFinalizada",
        "${releaseOsId}:ReservaPecasLiberada")) {
    if (-not $lateEvents.Contains($expected)) { throw "Evento ausente: $expected" }
}
$markerKey = @{ messageId = @{ S = $commandId } } | ConvertTo-Json -Depth 4 -Compress
$marker = Invoke-LocalAws @('dynamodb', 'get-item', '--table-name', 'execucao-idempotencia',
    '--key', $markerKey, '--output', 'json') | ConvertFrom-Json
if (-not $marker.Item.messageId.S) { throw 'Diagnosis command idempotency marker is missing.' }
Write-Host "PASS osId=$osId pecaId=$pecaId; diagnóstico, reserva, recusa, início, finalização e liberação verificados localmente."
