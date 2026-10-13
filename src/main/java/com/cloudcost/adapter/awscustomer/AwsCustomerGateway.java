package com.cloudcost.adapter.awscustomer;

import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * 우리 백엔드 자격증명(로컬: AWS_PROFILE, 배포: ECS task role) → 고객 Role AssumeRole(+External ID).
 *
 * <p>open() 안에서 AssumeRole을 실제로 수행해 성공 여부를 바로 판단한다.
 * 받은 임시 자격증명은 StsAssumeRoleCredentialsProvider 인스턴스 안에만 있고 DB·로그·응답으로 나가지 않는다.
 */
@Component
class AwsCustomerGateway implements CustomerAwsGateway, AutoCloseable {

    private final AwsCredentialsProvider backendCredentials;
    private final int durationSeconds;
    private final ClientOverrideConfiguration clientConfig;

    AwsCustomerGateway(@Value("${cci.aws.assume-role-duration-seconds:900}") int durationSeconds) {
        this.backendCredentials = DefaultCredentialsProvider.builder().build();
        this.durationSeconds = durationSeconds;
        this.clientConfig = ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(30))
            .apiCallAttemptTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public CustomerAwsSession open(RoleArn roleArn, ExternalId externalId, AwsRegion region, String sessionName) {
        Region awsRegion = Region.of(region.value());
        StsClient sts = StsClient.builder()
            .region(awsRegion)
            .credentialsProvider(backendCredentials)
            .overrideConfiguration(clientConfig)
            .build();
        StsAssumeRoleCredentialsProvider credentials = StsAssumeRoleCredentialsProvider.builder()
            .stsClient(sts)
            .asyncCredentialUpdateEnabled(false)
            .refreshRequest(AssumeRoleRequest.builder()
                .roleArn(roleArn.value())
                .externalId(externalId.value())
                .roleSessionName(sessionName.length() > 64 ? sessionName.substring(0, 64) : sessionName)
                .durationSeconds(durationSeconds)
                .build())
            .build();
        try {
            // AssumeRole을 지금 수행한다. 반환값(자격증명)은 변수에 담지 않고 버린다.
            credentials.resolveCredentials();
        } catch (RuntimeException e) {
            credentials.close();
            sts.close();
            throw CustomerAwsErrors.translate(e, "sts:AssumeRole");
        }
        return new AwsCustomerSession(awsRegion, credentials, clientConfig, List.of(credentials, sts));
    }

    @Override
    public void close() {
        if (backendCredentials instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 종료 중
            }
        }
    }
}
