package com.cloudcost.application.port;

import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceScan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 방치 리소스 후보와 확장 수집 상태 (ADR-020). 모든 조회·수정은 owner 조건을 건다 */
public interface AccountResourceRepository {

    Optional<ResourceScan> findScanByOwnerIdAndAccountId(String ownerId, UUID accountId);

    /** 상태만 바꾼다 (실패·권한 부족: 이전 리소스 목록은 그대로 둔다) */
    void saveScan(String ownerId, UUID accountId, ResourceScan scan);

    /** 성공한 수집 결과로 그 연결의 리소스 목록을 통째로 바꾸고 상태를 저장한다 (한 트랜잭션) */
    void replaceAll(String ownerId, UUID accountId, List<AwsResource> resources, ResourceScan scan, Instant observedAt);

    List<AwsResource> findAllByOwnerIdAndAccountId(String ownerId, UUID accountId);

    /** 연결 삭제용 */
    void deleteByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
