package com.cloudcost.application.port;

import com.cloudcost.domain.account.AwsAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 사용자 요청의 조회는 ownerId를 첫 인자로 받는다. owner 없이 id만으로 찾는 메서드는 두지 않는다.
 * 예외는 {@link #findByExternalIdForCallback} 하나다: CloudFormation 신호에는 owner가 없어서
 * 추측할 수 없는 External ID(96비트)와 연결 id를 함께 맞춰 본 뒤, owner는 찾은 행에서 읽는다.
 */
public interface AwsAccountRepository {

    AwsAccount save(AwsAccount account);

    Optional<AwsAccount> findByOwnerIdAndId(String ownerId, UUID id);

    List<AwsAccount> findAllByOwnerId(String ownerId);

    /**
     * 같은 사용자가 같은 AWS 계정·리전을 이미 ACTIVE로 연결했는지 (excludeId 제외).
     */
    boolean existsActiveByOwnerIdAndAwsAccount(String ownerId, String awsAccountId, String region, UUID excludeId);

    /** 스택 신호 처리 전용. 사용자 요청 경로에서는 쓰지 않는다. */
    Optional<AwsAccount> findByExternalIdForCallback(String externalId);
}
