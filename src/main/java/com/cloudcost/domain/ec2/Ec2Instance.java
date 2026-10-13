package com.cloudcost.domain.ec2;

import java.time.Instant;

/**
 * DescribeInstances 응답에서 꺼낸 인스턴스 정보. 저장 허용 필드만 둔다.
 *
 * <p>IP, 키페어, 보안그룹, 전체 태그, 사용자 데이터는 의도적으로 없다 (docs/SECURITY.md).
 *
 * @param launchTime      마지막으로 시작한 시각. stop 후 start하면 바뀐다.
 * @param platformDetails 예: "Linux/UNIX", "Windows", "Red Hat Enterprise Linux"
 * @param tenancy         default | dedicated | host
 * @param spot            instanceLifecycle == spot
 */
public record Ec2Instance(
    String instanceId,
    String name,
    String instanceType,
    String region,
    String availabilityZone,
    String state,
    Instant launchTime,
    String platformDetails,
    String tenancy,
    boolean spot,
    boolean detailedMonitoring) {

    public boolean isRunning() {
        return InstanceStates.isRunning(state);
    }
}
