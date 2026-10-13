package com.cloudcost.adapter.awsinternal;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.account.StackCallback;
import com.cloudcost.domain.account.StackCallback.RequestType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StackCallbackAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String REQUEST = """
            {"RequestType":"Create",
             "ServiceToken":"arn:aws:sns:ap-northeast-2:111122223333:cloudcost-insight-connections",
             "ResponseURL":"https://cloudformation-custom-resource-response-apnortheast2.s3.ap-northeast-2.amazonaws.com/arn%3Aaws?X-Amz-Signature=abc",
             "StackId":"arn:aws:cloudformation:ap-northeast-2:123456789012:stack/CloudCostInsight/1f2e",
             "RequestId":"req-1","LogicalResourceId":"ConnectionNotice",
             "ResourceType":"Custom::CloudCostInsightConnection",
             "ResourceProperties":{"ServiceToken":"…","ConnectionId":"6fc3cd34-49c7-47ca-b680-98f123ecd1e6",
               "ExternalId":"cci-0123456789abcdef01234567","RoleArn":"arn:aws:iam::123456789012:role/CloudCostReadOnlyRole"}}
            """;

    @Test
    void SNS_봉투_안의_CloudFormation_요청을_꺼낸다() throws Exception {
        String envelope = mapper.writeValueAsString(java.util.Map.of(
                "Type", "Notification", "MessageId", "m-1", "TopicArn", "arn:aws:sns:…", "Message", REQUEST));

        StackCallback callback = StackCallbackParser.parse(envelope, mapper);

        assertThat(callback).isNotNull();
        assertThat(callback.requestType()).isEqualTo(RequestType.CREATE);
        assertThat(callback.connectionId()).isEqualTo("6fc3cd34-49c7-47ca-b680-98f123ecd1e6");
        assertThat(callback.externalId()).isEqualTo("cci-0123456789abcdef01234567");
        assertThat(callback.roleArn()).isEqualTo("arn:aws:iam::123456789012:role/CloudCostReadOnlyRole");
        assertThat(callback.stackAccountId()).isEqualTo("123456789012");
        assertThat(callback.stablePhysicalId()).isEqualTo("cci-connection-6fc3cd34-49c7-47ca-b680-98f123ecd1e6");
    }

    @Test
    void Raw_전달도_읽고_알_수_없는_메시지는_null() {
        assertThat(StackCallbackParser.parse(REQUEST, mapper)).isNotNull();
        assertThat(StackCallbackParser.parse("{\"hello\":1}", mapper)).isNull();
        assertThat(StackCallbackParser.parse("not json", mapper)).isNull();
        assertThat(StackCallbackParser.parse(REQUEST.replace("\"Create\"", "\"Explode\""), mapper)).isNull();
    }

    @Test
    void CloudFormation_응답용_S3_주소가_아니면_요청하지_않는다_SSRF_방지() {
        assertThat(CloudFormationResponder.allowedResponseUri(
                "https://cloudformation-custom-resource-response-apnortheast2.s3.ap-northeast-2.amazonaws.com/x?sig=1")).isNotNull();
        assertThat(CloudFormationResponder.allowedResponseUri(
                "https://cloudformation-custom-resource-response-useast1.s3.amazonaws.com/x")).isNotNull();
        assertThat(CloudFormationResponder.allowedResponseUri("http://cloudformation-custom-resource-response-useast1.s3.amazonaws.com/x")).isNull();
        assertThat(CloudFormationResponder.allowedResponseUri("https://169.254.169.254/latest/meta-data")).isNull();
        assertThat(CloudFormationResponder.allowedResponseUri("https://evil.example.com/cloudformation-custom-resource-response")).isNull();
        assertThat(CloudFormationResponder.allowedResponseUri(
                "https://cloudformation-custom-resource-response-x.s3.amazonaws.com.evil.com/x")).isNull();
        assertThat(CloudFormationResponder.allowedResponseUri(null)).isNull();
    }
}
