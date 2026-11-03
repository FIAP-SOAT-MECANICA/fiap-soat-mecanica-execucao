package br.com.fiap.soat.mecanica.diagnostico;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration
class AwsClientsConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean DynamoDbClient dynamoDbClient(@Value("${execucao.aws.region}") String region,
            @Value("${execucao.aws.endpoint:}") String endpoint,
            @Value("${execucao.aws.access-key:}") String accessKey,
            @Value("${execucao.aws.secret-key:}") String secretKey) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank() && !secretKey.isBlank())
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        return builder.build();
    }

    @Bean SqsClient sqsClient(@Value("${execucao.aws.region}") String region,
            @Value("${execucao.aws.endpoint:}") String endpoint,
            @Value("${execucao.aws.access-key:}") String accessKey,
            @Value("${execucao.aws.secret-key:}") String secretKey) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank() && !secretKey.isBlank())
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        return builder.build();
    }

    @Bean SnsClient snsClient(@Value("${execucao.aws.region}") String region,
            @Value("${execucao.aws.endpoint:}") String endpoint,
            @Value("${execucao.aws.access-key:}") String accessKey,
            @Value("${execucao.aws.secret-key:}") String secretKey) {
        SnsClientBuilder builder = SnsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKey.isBlank() && !secretKey.isBlank())
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        return builder.build();
    }
}
