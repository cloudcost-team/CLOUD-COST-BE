package com.cloudcost.entrypoint.config;

import com.cloudcost.application.ConnectionSettings;
import com.cloudcost.application.UsageSettings;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 환경별 설정 (application.yml ← 환경변수). 비밀값과 실제 계정 ARN은 코드에 두지 않는다.
 *
 * @param appEnv local | dev | prod
 */
@ConfigurationProperties(prefix = "cci")
public record CloudCostProperties(
        @DefaultValue("local") String appEnv, @DefaultValue Auth auth, @DefaultValue Aws aws, @DefaultValue Usage usage) {

    /**
     * @param mode dev: Bearer 토큰이 없으면 X-Owner-Id 헤더로 사용자 구분 (APP_ENV=local 전용) / google: Bearer 토큰 필수
     * @param googleClientId Google OAuth 웹 클라이언트 ID (공개 값, GOOGLE_CLIENT_ID)
     * @param jwtSecret 액세스 토큰 서명 키 (32바이트 이상, JWT_SECRET). google 모드에서는 필수
     */
    public record Auth(
            @DefaultValue("dev") String mode,
            @DefaultValue("local-dev-user") String devDefaultOwner,
            String googleClientId,
            String jwtSecret,
            @DefaultValue("1h") Duration tokenTtl) {

        public boolean devMode() {
            return "dev".equalsIgnoreCase(mode);
        }
    }

    /**
     * @param backendRoleArn 고객 Trust Policy의 Principal (CCI_BACKEND_ROLE_ARN)
     * @param templateUrl S3에 올린 고객용 CloudFormation 템플릿 (CCI_TEMPLATE_URL)
     * @param callbackTopicArn 스택 신호를 받는 SNS 토픽 (CCI_CALLBACK_TOPIC_ARN)
     * @param callbackQueueUrl SNS가 전달하는 SQS 큐. 비어 있으면 신호 수신기를 켜지 않는다 (CCI_CALLBACK_QUEUE_URL)
     */
    public record Aws(
            String backendRoleArn,
            String templateUrl,
            String callbackTopicArn,
            String callbackQueueUrl,
            @DefaultValue("6") int verifyAttempts,
            @DefaultValue("10s") Duration verifyInterval) {}

    public record Usage(
            @DefaultValue("10m") Duration instanceTtl,
            @DefaultValue("1h") Duration usageTtl,
            @DefaultValue("90d") Duration backfill,
            @DefaultValue("2h") Duration overlap,
            @DefaultValue("500") int maxCwRequestsPerRefresh,
            @DefaultValue("Asia/Seoul") ZoneId zone,
            @DefaultValue("7d") Duration priceTtl,
            @DefaultValue("1d") Duration missingPriceTtl) {}

    public ConnectionSettings connectionSettings() {
        return new ConnectionSettings(
                aws.backendRoleArn(), aws.templateUrl(), aws.callbackTopicArn(), aws.verifyAttempts(),
                aws.verifyInterval());
    }

    public UsageSettings usageSettings() {
        if (usage.backfill().compareTo(Duration.ofDays(455)) > 0) {
            throw new IllegalStateException("cci.usage.backfill은 455일을 넘을 수 없습니다 (CloudWatch 1시간 지표 보관 기간).");
        }
        return new UsageSettings(
                usage.instanceTtl(), usage.usageTtl(), usage.backfill(), usage.overlap(),
                usage.maxCwRequestsPerRefresh(), usage.zone(), usage.priceTtl(), usage.missingPriceTtl());
    }
}
