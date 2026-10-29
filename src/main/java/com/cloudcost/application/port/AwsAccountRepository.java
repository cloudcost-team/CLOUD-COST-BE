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

    /** 새 연결을 저장한다. */
    AwsAccount add(AwsAccount account);

    /**
     * 이미 있는 연결을 갱신한다. 그 사이 사용자가 연결을 삭제했으면 되살리지 않고 아무것도 하지 않는다
     * (진행 중이던 수집·스택 신호 처리가 삭제한 연결을 다시 만들지 않게 하기 위해서다).
     */
    AwsAccount save(AwsAccount account);

    /** @return 지웠으면 true. 남의 연결이면 false (아무것도 지우지 않는다). */
    boolean deleteByOwnerIdAndId(String ownerId, UUID id);

    Optional<AwsAccount> findByOwnerIdAndId(String ownerId, UUID id);

    List<AwsAccount> findAllByOwnerId(String ownerId);

    /**
     * 같은 사용자가 같은 AWS 계정·리전을 이미 ACTIVE로 연결했는지 (excludeId 제외).
     */
    boolean existsActiveByOwnerIdAndAwsAccount(String ownerId, String awsAccountId, String region, UUID excludeId);

    /** 스택 신호 처리 전용. 사용자 요청 경로에서는 쓰지 않는다. */
    Optional<AwsAccount> findByExternalIdForCallback(String externalId);
}
