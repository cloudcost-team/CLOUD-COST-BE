package com.cloudcost.adapter.awsinternal;

import com.cloudcost.application.port.StackCallbackResponder;
import com.cloudcost.domain.account.StackCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Custom Resource 결과를 CloudFormation의 ResponseURL(S3 presigned URL)로 PUT한다.
 *
 * <p>ResponseURL은 누구나 보낼 수 있는 SNS 메시지에서 오므로, CloudFormation 응답용 S3 주소가 아니면 요청하지 않는다
 * (임의 주소로 요청을 보내게 만드는 SSRF 방지).
 */
@Component
class CloudFormationResponder implements StackCallbackResponder {

    static final Pattern ALLOWED_HOST =
            Pattern.compile("^cloudformation-custom-resource-response-[a-z0-9-]+\\.s3(\\.[a-z0-9-]+)?\\.amazonaws\\.com$");

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CloudFormationResponder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void respond(StackCallback callback, boolean success, String reason) {
        URI uri = allowedResponseUri(callback.responseUrl());
        if (uri == null) {
            // 진짜 CloudFormation 요청이 아니다. 응답하지 않고 버린다.
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Status", success ? "SUCCESS" : "FAILED");
        body.put("Reason", reason == null ? "OK" : truncate(reason, 1000));
        body.put("PhysicalResourceId", callback.physicalResourceId() != null && callback.requestType()
                == StackCallback.RequestType.DELETE ? callback.physicalResourceId() : callback.stablePhysicalId());
        body.put("StackId", callback.stackId());
        body.put("RequestId", callback.requestId());
        body.put("LogicalResourceId", callback.logicalResourceId());
        body.put("NoEcho", false);
        body.put("Data", Map.of());
        try {
            // presigned URL은 Content-Type 없이 서명되므로 Content-Type 헤더를 넣지 않는다.
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("CloudFormation 응답 실패: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CloudFormation 응답 중단", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("CloudFormation 응답 전송 실패", e);
        }
    }

    static URI allowedResponseUri(String responseUrl) {
        try {
            URI uri = URI.create(responseUrl);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null
                    && ALLOWED_HOST.matcher(uri.getHost()).matches() && uri.getPort() == -1) {
                return uri;
            }
        } catch (RuntimeException ignored) {
            // 형식 오류
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
