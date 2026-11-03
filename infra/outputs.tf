output "table_names" {
  value = {
    ordens       = aws_dynamodb_table.ordens.name
    pecas        = aws_dynamodb_table.pecas.name
    reservas     = aws_dynamodb_table.reservas.name
    outbox       = aws_dynamodb_table.outbox.name
    idempotencia = aws_dynamodb_table.idempotencia.name
  }
}

output "command_queue_url" {
  value = aws_sqs_queue.comandos.url
}

output "command_queue_arn" {
  value = aws_sqs_queue.comandos.arn
}

output "command_dlq_url" {
  value = aws_sqs_queue.comandos_dlq.url
}

output "event_topic_arn" {
  value = aws_sns_topic.eventos.arn
}

output "ecr_repository_url" {
  value = aws_ecr_repository.execucao.repository_url
}

output "ssm_parameter_names" {
  value = {
    command_queue_url = aws_ssm_parameter.command_queue_url.name
    event_topic_arn   = aws_ssm_parameter.event_topic_arn.name
  }
}
