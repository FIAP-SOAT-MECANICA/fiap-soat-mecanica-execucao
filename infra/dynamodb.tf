# Nomes de tabelas seguem os defaults em application.properties.
resource "aws_dynamodb_table" "ordens" {
  name         = "execucao-ordens"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "osId"

  attribute {
    name = "osId"
    type = "S"
  }

  # Itens ativos usam filaStatus=ATIVA e filaOrdem=<rank>#<ISO UTC>#<osId>.
  # A ausencia dessas chaves remove o item do indice esparso.
  attribute {
    name = "filaStatus"
    type = "S"
  }

  attribute {
    name = "filaOrdem"
    type = "S"
  }

  global_secondary_index {
    name            = "fila"
    projection_type = "ALL"

    key_schema {
      attribute_name = "filaStatus"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "filaOrdem"
      key_type       = "RANGE"
    }
  }
}

resource "aws_dynamodb_table" "pecas" {
  name         = "execucao-pecas"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pecaId"

  attribute {
    name = "pecaId"
    type = "S"
  }
}

resource "aws_dynamodb_table" "reservas" {
  name         = "execucao-reservas"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "osId"
  range_key    = "pecaId"

  attribute {
    name = "osId"
    type = "S"
  }

  attribute {
    name = "pecaId"
    type = "S"
  }
}

resource "aws_dynamodb_table" "outbox" {
  name         = "execucao-outbox"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "eventId"

  attribute {
    name = "eventId"
    type = "S"
  }
}

resource "aws_dynamodb_table" "idempotencia" {
  name         = "execucao-idempotencia"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "messageId"

  attribute {
    name = "messageId"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
}
