resource "aws_sns_topic" "eventos" {
  name = "${var.project}-execucao-eventos"
}

resource "aws_sqs_queue" "comandos_dlq" {
  name                      = "${var.project}-execucao-comandos-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "comandos" {
  name                       = "${var.project}-execucao-comandos"
  visibility_timeout_seconds = 300
  receive_wait_time_seconds  = 10
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.comandos_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "comandos_dlq" {
  queue_url = aws_sqs_queue.comandos_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.comandos.arn]
  })
}

# O repositorio OS cria a assinatura em seu proprio state depois deste topico.
resource "aws_ssm_parameter" "command_queue_url" {
  name  = "/${var.project}/execucao/command-queue-url"
  type  = "String"
  value = aws_sqs_queue.comandos.url
}

resource "aws_ssm_parameter" "event_topic_arn" {
  name  = "/${var.project}/execucao/event-topic-arn"
  type  = "String"
  value = aws_sns_topic.eventos.arn
}
