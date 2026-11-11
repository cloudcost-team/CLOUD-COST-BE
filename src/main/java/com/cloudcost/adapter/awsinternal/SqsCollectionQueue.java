package com.cloudcost.adapter.awsinternal;

import com.cloudcost.application.port.CollectionQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * 예약 수집 작업 큐 (우리 계정 SQS, ADR-019). 재시도·DLQ는 큐의 재전송 정책(maxReceiveCount 3)이 맡는다.
 * 필요한 백엔드 권한: sqs:SendMessage, sqs:ReceiveMessage, sqs:DeleteMessage (이 큐에만).
 * 메시지에는 owner와 연결 id만 넣는다 (자격증명·External ID 없음).
 */
@Component
@ConditionalOnExpression("!'${cci.aws.collection-queue-url:}'.isEmpty()")
class SqsCollectionQueue implements CollectionQueue, AutoCloseable {

    private static final Pattern QUEUE_REGION = Pattern.compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/.+$");

    private final String queueUrl;
    private final ObjectMapper mapper;
    private final DefaultCredentialsProvider credentials = DefaultCredentialsProvider.builder().build();
    private final SqsClient sqs;

    SqsCollectionQueue(@Value("${cci.aws.collection-queue-url}") String queueUrl, ObjectMapper mapper) {
        Matcher m = QUEUE_REGION.matcher(queueUrl.trim());
        if (!m.matches()) {
            throw new IllegalStateException(
                "CCI_COLLECTION_QUEUE_URL 형식이 올바르지 않습니다 (https://sqs.<region>.amazonaws.com/...).");
        }
        this.queueUrl = queueUrl.trim();
        this.mapper = mapper;
        this.sqs = SqsClient.builder()
            .region(Region.of(m.group(1)))
            .credentialsProvider(credentials)
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).build())
            .build();
    }

    @Override
    public void enqueue(String ownerId, UUID accountId) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("ownerId", ownerId, "accountId", accountId.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("수집 작업을 만들지 못했습니다.", e);
        }
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(body));
    }

    @Override
    public List<Received> receive() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(20)
                // 수집 1회(CloudWatch 최대 500건 + 재시도)보다 길게 잡아 처리 중에 다시 받지 않게 한다
                .visibilityTimeout(900)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build())
            .messages().stream()
            .map(this::parse)
            .toList();
    }

    Received parse(Message m) {
        int count = parseCount(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        try {
            JsonNode node = mapper.readTree(m.body());
            String owner = node.path("ownerId").asText(null);
            UUID account = UUID.fromString(node.path("accountId").asText(""));
            if (owner == null || owner.isBlank()) {
                return new Received(m.receiptHandle(), null, null, count);
            }
            return new Received(m.receiptHandle(), owner, account, count);
        } catch (Exception e) {
            return new Received(m.receiptHandle(), null, null, count);
        }
    }

    private static int parseCount(String value) {
        try {
            return value == null ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public void delete(String receiptHandle) {
        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(receiptHandle));
    }

    @Override
    public void close() {
        sqs.close();
        credentials.close();
    }
}
