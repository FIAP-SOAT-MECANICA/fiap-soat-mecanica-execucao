# Run with dot sourcing to retain the application environment:
# . .\scripts\setup-localstack.ps1
$ErrorActionPreference = 'Stop'
$env:AWS_ACCESS_KEY_ID = 'test'
$env:AWS_SECRET_ACCESS_KEY = 'test'
$env:AWS_REGION = 'us-east-1'
$env:AWS_DEFAULT_REGION = 'us-east-1'
$env:AWS_ENDPOINT = 'http://localhost:4566'
$endpoint = $env:AWS_ENDPOINT

function Invoke-LocalAws {
    param([string[]] $Arguments)
    $output = & aws --endpoint-url $endpoint @Arguments
    if ($LASTEXITCODE -ne 0) { throw "LocalStack AWS CLI failed: $($Arguments -join ' ')" }
    return $output
}

docker compose -f compose.localstack.yaml up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'LocalStack did not become healthy.' }

function Ensure-Table {
    param([string] $Name, [string[]] $Keys, [string[]] $Definitions, [string] $Gsi = '')
    & aws --endpoint-url $endpoint dynamodb describe-table --table-name $Name --output text 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { return }
    $arguments = @('dynamodb', 'create-table', '--table-name', $Name,
        '--billing-mode', 'PAY_PER_REQUEST', '--key-schema') + $Keys +
        @('--attribute-definitions') + $Definitions
    if ($Gsi) { $arguments += @('--global-secondary-indexes', $Gsi) }
    Invoke-LocalAws $arguments | Out-Null
}

Ensure-Table 'execucao-ordens' @('AttributeName=osId,KeyType=HASH') `
    @('AttributeName=osId,AttributeType=S', 'AttributeName=filaStatus,AttributeType=S', 'AttributeName=filaOrdem,AttributeType=S') `
    '[{"IndexName":"fila","KeySchema":[{"AttributeName":"filaStatus","KeyType":"HASH"},{"AttributeName":"filaOrdem","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]'
Ensure-Table 'execucao-idempotencia' @('AttributeName=messageId,KeyType=HASH') @('AttributeName=messageId,AttributeType=S')
Ensure-Table 'execucao-outbox' @('AttributeName=eventId,KeyType=HASH') @('AttributeName=eventId,AttributeType=S')
Ensure-Table 'execucao-pecas' @('AttributeName=pecaId,KeyType=HASH') @('AttributeName=pecaId,AttributeType=S')
Ensure-Table 'execucao-reservas' @('AttributeName=osId,KeyType=HASH', 'AttributeName=pecaId,KeyType=RANGE') `
    @('AttributeName=osId,AttributeType=S', 'AttributeName=pecaId,AttributeType=S')
Invoke-LocalAws @('dynamodb', 'update-time-to-live', '--table-name', 'execucao-idempotencia',
    '--time-to-live-specification', 'Enabled=true,AttributeName=expiresAt') | Out-Null

$queueName = 'mecanica-execucao-comandos'
$dlqName = "$queueName-dlq"
$responseName = 'os-saga-respostas-demo'
foreach ($name in @($dlqName, $queueName, $responseName)) {
    Invoke-LocalAws @('sqs', 'create-queue', '--queue-name', $name) | Out-Null
}
$dlqUrl = (Invoke-LocalAws @('sqs', 'get-queue-url', '--queue-name', $dlqName, '--query', 'QueueUrl', '--output', 'text')).Trim()
$env:EXECUCAO_COMMAND_QUEUE_URL = (Invoke-LocalAws @('sqs', 'get-queue-url', '--queue-name', $queueName, '--query', 'QueueUrl', '--output', 'text')).Trim()
$env:DEMO_RESPONSE_QUEUE_URL = (Invoke-LocalAws @('sqs', 'get-queue-url', '--queue-name', $responseName, '--query', 'QueueUrl', '--output', 'text')).Trim()
$dlqArn = (Invoke-LocalAws @('sqs', 'get-queue-attributes', '--queue-url', $dlqUrl,
    '--attribute-names', 'QueueArn', '--query', 'Attributes.QueueArn', '--output', 'text')).Trim()
$redrive = @{ deadLetterTargetArn = $dlqArn; maxReceiveCount = 5 } | ConvertTo-Json -Compress
$attributesFile = Join-Path $env:TEMP 'execucao-localstack-queue-attributes.json'
@{ RedrivePolicy = $redrive; VisibilityTimeout = '30' } |
    ConvertTo-Json -Compress | Set-Content -Path $attributesFile -Encoding utf8
try {
    Invoke-LocalAws @('sqs', 'set-queue-attributes', '--queue-url', $env:EXECUCAO_COMMAND_QUEUE_URL,
        '--attributes', "file://$attributesFile") | Out-Null
} finally { Remove-Item -LiteralPath $attributesFile -ErrorAction SilentlyContinue }
$responseArn = (Invoke-LocalAws @('sqs', 'get-queue-attributes', '--queue-url', $env:DEMO_RESPONSE_QUEUE_URL,
    '--attribute-names', 'QueueArn', '--query', 'Attributes.QueueArn', '--output', 'text')).Trim()
$env:EXECUCAO_EVENT_TOPIC_ARN = (Invoke-LocalAws @('sns', 'create-topic', '--name', 'mecanica-execucao-eventos',
    '--query', 'TopicArn', '--output', 'text')).Trim()
$subscriptions = Invoke-LocalAws @('sns', 'list-subscriptions-by-topic', '--topic-arn', $env:EXECUCAO_EVENT_TOPIC_ARN,
    '--query', 'Subscriptions[].Endpoint', '--output', 'text')
if (-not ($subscriptions -like "*$responseArn*")) {
    Invoke-LocalAws @('sns', 'subscribe', '--topic-arn', $env:EXECUCAO_EVENT_TOPIC_ARN,
        '--protocol', 'sqs', '--notification-endpoint', $responseArn,
        '--attributes', 'RawMessageDelivery=true') | Out-Null
}
Write-Host 'LocalStack resources are ready. Environment variables are set in this PowerShell session.'
Write-Host 'Start the service here: .\mvnw.cmd spring-boot:run'
Write-Host 'Then run in another shell: .\scripts\demo-localstack.ps1'
Write-Host "Command queue: $env:EXECUCAO_COMMAND_QUEUE_URL"
Write-Host "Response queue: $env:DEMO_RESPONSE_QUEUE_URL"
