package br.com.fiap.soat.mecanica.diagnostico;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
class DiagnosticoCommandConsumer {
    private final SqsClient sqs;
    private final ObjectMapper mapper;
    private final AbrirDiagnosticoUseCase abrirDiagnostico;
    private final EstoqueCommandHandler estoque;
    private final String queueUrl;

    DiagnosticoCommandConsumer(SqsClient sqs, ObjectMapper mapper, AbrirDiagnosticoUseCase abrirDiagnostico,
            EstoqueCommandHandler estoque,
            @Value("${execucao.diagnostico.command-queue-url:}") String queueUrl) {
        this.sqs = sqs;
        this.mapper = mapper;
        this.abrirDiagnostico = abrirDiagnostico;
        this.estoque = estoque;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${execucao.diagnostico.poll-delay-ms:1000}")
    void poll() {
        if (queueUrl.isBlank()) return;
        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(10).waitTimeSeconds(10).build()).messages();
        for (var message : messages) {
            try {
                MessageEnvelope command = parse(message.body());
                if ("AbrirDiagnostico".equals(command.type())) abrirDiagnostico.executar(command);
                else if (estoque.supports(command.type())) estoque.executar(command);
                else throw new IllegalArgumentException("Tipo de comando desconhecido: " + command.type());
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle()).build());
            } catch (RuntimeException e) {
                // SQS retries and eventually moves the message to its configured DLQ.
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Falha ao processar comando de execução", e);
            }
        }
    }

    private MessageEnvelope parse(String body) {
        try { return mapper.readValue(body, MessageEnvelope.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Envelope inválido", e); }
    }
}
