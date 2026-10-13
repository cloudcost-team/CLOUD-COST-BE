package com.cloudcost.adapter.awsinternal;

import com.cloudcost.application.port.StackCallbackInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * 우리 계정 SQS 큐에서 스택 신호를 받는다 (SNS → SQS). 서버가 꺼내러 가는 방식이라 서버를 인터넷에 공개하지 않아도 된다.
 * 필요한 백엔드 권한: sqs:ReceiveMessage, sqs:DeleteMessage (이 큐에만).
 */
@Component
@ConditionalOnExpression("!'${cci.aws.callback-queue-url:}'.isEmpty()")
class SqsStackCallbackInbox implements StackCallbackInbox, AutoCloseable {

    private static final Pattern QUEUE_REGION = Pattern.compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/.+$");

    private final String queueUrl;
    private final ObjectMapper mapper;
    private final DefaultCredentialsProvider credentials = DefaultCredentialsProvider.builder().build();
    private final SqsClient sqs;

    SqsStackCallbackInbox(@Value("${cci.aws.callback-queue-url}") String queueUrl, ObjectMapper mapper) {
        Matcher m = QUEUE_REGION.matcher(queueUrl.trim());
        if (!m.matches()) {
            throw new IllegalStateException("CCI_CALLBACK_QUEUE_URL 형식이 올바르지 않습니다 (https://sqs.<region>.amazonaws.com/...).");
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
    public List<Received> receive() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(20)
                        // 재시도(최대 약 1분)를 포함한 처리 시간보다 길게 잡아 중복 처리를 막는다.
                        .visibilityTimeout(180)
                        .build())
                .messages().stream()
                .map(m -> new Received(m.receiptHandle(), StackCallbackParser.parse(m.body(), mapper)))
                .toList();
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
