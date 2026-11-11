param([string] $BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$env:AWS_ACCESS_KEY_ID = 'test'
$env:AWS_SECRET_ACCESS_KEY = 'test'
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

$osId = [guid]::NewGuid().ToString()
$sagaId = [guid]::NewGuid().ToString()
$commandId = [guid]::NewGuid().ToString()
$pecaId = [guid]::NewGuid().ToString()
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

$item = @{ pecaId = @{ S = $pecaId }; nome = @{ S = 'Filtro de óleo' }
    valorUnitario = @{ N = '29.90' }; qtdEstoque = @{ N = '1' }
    qtdDisponivel = @{ N = '1' }; qtdReservada = @{ N = '0' } } | ConvertTo-Json -Depth 6 -Compress
Invoke-LocalAws @('dynamodb', 'put-item', '--table-name', 'execucao-pecas', '--item', $item) | Out-Null
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

$eventTypes = [System.Collections.Generic.HashSet[string]]::new()
for ($attempt = 0; $attempt -lt 10 -and $eventTypes.Count -lt 2; $attempt++) {
    $received = Invoke-LocalAws @('sqs', 'receive-message', '--queue-url', $responseQueue,
        '--wait-time-seconds', '2', '--max-number-of-messages', '10', '--output', 'json') | ConvertFrom-Json
    foreach ($message in $received.Messages) {
        $event = $message.Body | ConvertFrom-Json
        if ($event.osId -eq $osId) { [void] $eventTypes.Add($event.type) }
        Invoke-LocalAws @('sqs', 'delete-message', '--queue-url', $responseQueue,
            '--receipt-handle', $message.ReceiptHandle) | Out-Null
    }
}
if (-not $eventTypes.Contains('DiagnosticoAberto') -or -not $eventTypes.Contains('PecasReservadas')) {
    throw "Expected DiagnosticoAberto and PecasReservadas; observed: $($eventTypes -join ', ')"
}

# A second OS cannot reserve the last unit. The original reservation can be released.
$otherOsId = [guid]::NewGuid().ToString()
$refusal = @{
    messageId = [guid]::NewGuid().ToString(); type = 'ReservarPecas'; version = 1
    sagaId = [guid]::NewGuid().ToString(); osId = $otherOsId; correlationId = "demo-$otherOsId"
    occurredAt = [DateTime]::UtcNow.ToString('o')
    payload = @{ osId = $otherOsId; pecas = @(@{ pecaId = $pecaId; qtd = 1 }) }
} | ConvertTo-Json -Depth 8 -Compress
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $refusal) | Out-Null
$release = @{
    messageId = [guid]::NewGuid().ToString(); type = 'LiberarReservaPecas'; version = 1
    sagaId = $sagaId; osId = $osId; correlationId = "demo-$osId"
    occurredAt = [DateTime]::UtcNow.ToString('o'); payload = @{ osId = $osId }
} | ConvertTo-Json -Depth 8 -Compress
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
Invoke-LocalAws @('sqs', 'send-message', '--queue-url', $queue, '--message-body', $release) | Out-Null
$restored = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $stock = Invoke-LocalAws @('dynamodb', 'get-item', '--table-name', 'execucao-pecas', '--key', $key,
        '--output', 'json') | ConvertFrom-Json
    if ($stock.Item.qtdDisponivel.N -eq '1' -and $stock.Item.qtdReservada.N -eq '0') {
        $restored = $true; break
    }
    Start-Sleep -Seconds 1
}
if (-not $restored) { throw 'LiberarReservaPecas did not restore availability.' }
$markerKey = @{ messageId = @{ S = $commandId } } | ConvertTo-Json -Depth 4 -Compress
$marker = Invoke-LocalAws @('dynamodb', 'get-item', '--table-name', 'execucao-idempotencia',
    '--key', $markerKey, '--output', 'json') | ConvertFrom-Json
if (-not $marker.Item.messageId.S) { throw 'Diagnosis command idempotency marker is missing.' }
Write-Host "PASS osId=$osId pecaId=$pecaId; duplicate deduplicated; reserve, refusal and release verified; SNS events received."
