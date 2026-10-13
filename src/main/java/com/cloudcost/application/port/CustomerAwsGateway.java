package com.cloudcost.application.port;

import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;

/**
 * 고객 AWS 계정 접근 (구현: adapter/awscustomer).
 *
 * <p>open()이 STS AssumeRole을 실제로 수행한다. 실패하면 AwsAccessException(ASSUME_ROLE_DENIED 등)을 던진다.
 * 반환된 세션은 임시 자격증명을 내부에만 보관하며, 호출이 끝나면 반드시 close()한다.
 */
public interface CustomerAwsGateway {

    CustomerAwsSession open(RoleArn roleArn, ExternalId externalId, AwsRegion region, String sessionName);
}
