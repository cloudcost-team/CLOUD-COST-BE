package com.cloudcost.adapter.awscustomer;

import com.cloudcost.domain.ec2.Ec2Instance;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * SDK Instance → 도메인. 저장 허용 필드만 꺼낸다 (IP·키페어·보안그룹·태그 전체·사용자 데이터는 읽지 않는다).
 */
final class Ec2InstanceMapper {

    private Ec2InstanceMapper() {
    }

    static Ec2Instance toDomain(Instance i, String region) {
        String name = i.tags().stream()
            .filter(t -> "Name".equals(t.key()))
            .map(Tag::value)
            .findFirst()
            .orElse(null);
        String monitoring = i.monitoring() == null ? null : i.monitoring().stateAsString();
        return new Ec2Instance(
            i.instanceId(),
            name,
            i.instanceTypeAsString(),
            region,
            i.placement() == null ? null : i.placement().availabilityZone(),
            i.state() == null ? "unknown" : i.state().nameAsString(),
            i.launchTime(),
            i.platformDetails(),
            i.placement() == null ? null : i.placement().tenancyAsString(),
            "spot".equals(i.instanceLifecycleAsString()),
            "enabled".equals(monitoring) || "disabling".equals(monitoring));
    }
}
