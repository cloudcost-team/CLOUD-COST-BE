package com.cloudcost.domain.resource;

import java.time.Instant;

/**
 * Describe 응답에서 꺼낸 리소스 1개. 낭비 판정에 필요한 필드만 둔다 (IP 주소·태그 전체·보안그룹은 없다).
 *
 * @param resourceId   vol-… / eipalloc-… / snap-… / LB 이름 / i-…
 * @param name         Name 태그 (없으면 null, LB는 LB 이름)
 * @param subtype      볼륨 타입(gp3…) / LB 종류(application·network) / 인스턴스 타입
 * @param sizeGb       볼륨·스냅샷 크기 (GiB). 그 밖은 null
 * @param inUse        볼륨=연결됨, 고정 IP=연결됨, 인스턴스=실행 중. 스냅샷·LB는 true
 * @param createdAt    만든 시각 (고정 IP는 null, 인스턴스는 LaunchTime)
 * @param requestCount LB의 최근 14일 요청 수 (CloudWatch). 모르면 null
 * @param platform     인스턴스 platformDetails (단가 조회용). 그 밖은 null
 */
public record AwsResource(
    ResourceType type,
    String resourceId,
    String name,
    String region,
    String subtype,
    Integer sizeGb,
    boolean inUse,
    Instant createdAt,
    Long requestCount,
    String platform) {
}
