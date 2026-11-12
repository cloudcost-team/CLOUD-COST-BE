package com.cloudcost.adapter.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 인터페이스. CrudRepository/JpaRepository를 상속하지 않는다 — owner 조건 없는 findById·findAll이 생기지 않게 하기 위해서다 (ArchUnit
 * 테스트로 확인).
 */
final class SpringDataRepositories {

    private SpringDataRepositories() {
    }

    interface UserJpa extends Repository<UserEntity, String> {
        boolean existsById(String id);

        UserEntity save(UserEntity entity);

        /** 본인 조회: id가 곧 owner_id다 */
        Optional<UserEntity> findOneById(String id);

        Optional<UserEntity> findByAuthProviderAndProviderSubject(String authProvider, String providerSubject);
    }

    interface AwsAccountJpa extends Repository<AwsAccountEntity, UUID> {
        AwsAccountEntity save(AwsAccountEntity entity);

        Optional<AwsAccountEntity> findByOwnerIdAndId(String ownerId, UUID id);

        List<AwsAccountEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

        /** 스택 신호 처리 전용 (owner는 결과 행에서 읽는다) */
        Optional<AwsAccountEntity> findByExternalId(String externalId);

        /** 예약 수집 전용 (owner는 결과 행에서 읽는다). ACTIVE와, 확인된 적 있는 ERROR */
        @Query("select a from AwsAccountEntity a where a.status = 'ACTIVE' "
            + "or (a.status = 'ERROR' and a.verifiedAt is not null) order by a.createdAt")
        List<AwsAccountEntity> findCollectableForSchedule();

        boolean existsByOwnerIdAndAwsAccountIdAndRegionAndStatusAndIdNot(
            String ownerId, String awsAccountId, String region, String status, UUID id);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from AwsAccountEntity a where a.ownerId = :ownerId and a.id = :id")
        int deleteByOwnerIdAndId(@Param("ownerId") String ownerId, @Param("id") UUID id);
    }

    interface Ec2InstanceJpa extends Repository<Ec2InstanceEntity, UUID> {
        Ec2InstanceEntity save(Ec2InstanceEntity entity);

        List<Ec2InstanceEntity> findByOwnerIdAndAccountId(String ownerId, UUID accountId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from Ec2InstanceEntity e where e.ownerId = :ownerId and e.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface Ec2UsageHourlyJpa extends Repository<Ec2UsageHourlyEntity, Long> {
        Ec2UsageHourlyEntity save(Ec2UsageHourlyEntity entity);

        List<Ec2UsageHourlyEntity> findByOwnerIdAndAccountIdAndInstanceIdAndHourStartIn(
            String ownerId, UUID accountId, String instanceId, Collection<Instant> hourStarts);

        List<Ec2UsageHourlyEntity> findByOwnerIdAndAccountIdAndHourStartGreaterThanEqualAndHourStartLessThan(
            String ownerId, UUID accountId, Instant start, Instant end);

        @Query("select min(e.hourStart) from Ec2UsageHourlyEntity e "
            + "where e.ownerId = :ownerId and e.accountId = :accountId and e.sampleCount > 0")
        Instant findFirstUsedHour(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from Ec2UsageHourlyEntity e where e.ownerId = :ownerId and e.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface SyncJobJpa extends Repository<SyncJobEntity, UUID> {
        SyncJobEntity save(SyncJobEntity entity);

        List<SyncJobEntity> findByOwnerIdAndAccountIdOrderByStartedAtDesc(String ownerId, UUID accountId, Limit limit);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from SyncJobEntity e where e.ownerId = :ownerId and e.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface AccountBudgetJpa extends Repository<AccountBudgetEntity, UUID> {
        AccountBudgetEntity save(AccountBudgetEntity entity);

        Optional<AccountBudgetEntity> findByOwnerIdAndAccountId(String ownerId, UUID accountId);

        @Modifying
        @Query("delete from AccountBudgetEntity b where b.ownerId = :ownerId and b.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface AlertSettingsJpa extends Repository<AlertSettingsEntity, String> {
        AlertSettingsEntity save(AlertSettingsEntity entity);

        /** id가 곧 owner_id다 */
        Optional<AlertSettingsEntity> findOneByOwnerId(String ownerId);
    }

    interface NotificationJpa extends Repository<NotificationEntity, UUID> {
        NotificationEntity save(NotificationEntity entity);

        NotificationEntity saveAndFlush(NotificationEntity entity);

        boolean existsByOwnerIdAndEventKey(String ownerId, String eventKey);

        List<NotificationEntity> findByOwnerIdOrderByCreatedAtDescIdDesc(String ownerId, Limit limit);

        @Query("select n from NotificationEntity n where n.ownerId = :ownerId and (n.createdAt < :createdAt "
            + "or (n.createdAt = :createdAt and n.id < :id)) order by n.createdAt desc, n.id desc")
        List<NotificationEntity> findPageAfter(
            @Param("ownerId") String ownerId, @Param("createdAt") Instant createdAt, @Param("id") UUID id, Limit limit);

        long countByOwnerIdAndReadAtIsNull(String ownerId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update NotificationEntity n set n.readAt = :readAt where n.ownerId = :ownerId and n.readAt is null")
        int markAllRead(@Param("ownerId") String ownerId, @Param("readAt") Instant readAt);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update NotificationEntity n set n.readAt = :readAt "
            + "where n.ownerId = :ownerId and n.id = :id and n.readAt is null")
        int markRead(@Param("ownerId") String ownerId, @Param("id") UUID id, @Param("readAt") Instant readAt);

        boolean existsByOwnerIdAndId(String ownerId, UUID id);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from NotificationEntity n where n.ownerId = :ownerId and n.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface NotificationChannelJpa extends Repository<NotificationChannelEntity, UUID> {
        NotificationChannelEntity save(NotificationChannelEntity entity);

        Optional<NotificationChannelEntity> findByOwnerIdAndId(String ownerId, UUID id);

        List<NotificationChannelEntity> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from NotificationChannelEntity c where c.ownerId = :ownerId and c.id = :id")
        int deleteByOwnerIdAndId(@Param("ownerId") String ownerId, @Param("id") UUID id);
    }

    interface NotificationDeliveryJpa extends Repository<NotificationDeliveryEntity, UUID> {
        NotificationDeliveryEntity save(NotificationDeliveryEntity entity);

        NotificationDeliveryEntity saveAndFlush(NotificationDeliveryEntity entity);

        boolean existsByNotificationIdAndChannelId(UUID notificationId, UUID channelId);

        /** 발송 작업 전용: 켜진 채널 × 그 채널 등록 뒤(그리고 since 이후)에 생긴 알림 중 발송 기록이 없는 쌍 */
        @Query("select n.ownerId, n.id, c.id from NotificationEntity n, NotificationChannelEntity c "
            + "where c.ownerId = n.ownerId and c.enabled = true and n.createdAt >= c.createdAt "
            + "and n.createdAt >= :since and not exists (select d.id from NotificationDeliveryEntity d "
            + "where d.notificationId = n.id and d.channelId = c.id) order by n.createdAt")
        List<Object[]> findUndelivered(@Param("since") Instant since, Limit limit);

        /** 발송 작업 전용: 보낼 차례가 된 발송과 알림·채널·연결 이름 */
        @Query("select d, n, c, a.alias from NotificationDeliveryEntity d, NotificationEntity n, "
            + "NotificationChannelEntity c, AwsAccountEntity a "
            + "where n.id = d.notificationId and c.id = d.channelId and a.id = n.accountId "
            + "and d.ownerId = n.ownerId and c.ownerId = n.ownerId and a.ownerId = n.ownerId "
            + "and d.status = 'PENDING' and d.nextAttemptAt <= :now and c.enabled = true order by d.nextAttemptAt")
        List<Object[]> findDue(@Param("now") Instant now, Limit limit);

        List<NotificationDeliveryEntity> findByOwnerIdAndNotificationIdIn(String ownerId, Collection<UUID> ids);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from NotificationDeliveryEntity d where d.ownerId = :ownerId and d.channelId = :channelId")
        int deleteByOwnerIdAndChannelId(@Param("ownerId") String ownerId, @Param("channelId") UUID channelId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from NotificationDeliveryEntity d where d.ownerId = :ownerId and d.notificationId in "
            + "(select n.id from NotificationEntity n where n.ownerId = :ownerId and n.accountId = :accountId)")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface AccountResourceJpa extends Repository<AccountResourceEntity, UUID> {
        AccountResourceEntity save(AccountResourceEntity entity);

        List<AccountResourceEntity> findByOwnerIdAndAccountIdOrderByRegionAscTypeAscResourceIdAsc(
            String ownerId, UUID accountId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from AccountResourceEntity r where r.ownerId = :ownerId and r.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    interface AccountResourceScanJpa extends Repository<AccountResourceScanEntity, UUID> {
        AccountResourceScanEntity save(AccountResourceScanEntity entity);

        Optional<AccountResourceScanEntity> findByOwnerIdAndAccountId(String ownerId, UUID accountId);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from AccountResourceScanEntity s where s.ownerId = :ownerId and s.accountId = :accountId")
        int deleteByOwnerIdAndAccountId(@Param("ownerId") String ownerId, @Param("accountId") UUID accountId);
    }

    /** 리프레시 토큰: 조회는 토큰 해시(추측 불가)로만 한다 */
    interface RefreshTokenJpa extends Repository<RefreshTokenEntity, UUID> {
        RefreshTokenEntity save(RefreshTokenEntity entity);

        Optional<RefreshTokenEntity> findOneByTokenHash(String tokenHash);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update RefreshTokenEntity t set t.usedAt = :now "
            + "where t.id = :id and t.usedAt is null and t.revokedAt is null")
        int markUsed(@Param("id") UUID id, @Param("now") Instant now);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update RefreshTokenEntity t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
        int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("delete from RefreshTokenEntity t where t.ownerId = :ownerId and t.expiresAt <= :now")
        int deleteExpired(@Param("ownerId") String ownerId, @Param("now") Instant now);
    }

    /** 공개 환율 캐시 (owner 없음) */
    interface ExchangeRateJpa extends Repository<ExchangeRateEntity, UUID> {
        ExchangeRateEntity save(ExchangeRateEntity entity);

        Optional<ExchangeRateEntity> findFirstByBaseAndQuoteOrderByFetchedAtDesc(String base, String quote);

        Optional<ExchangeRateEntity> findByBaseAndQuoteAndRateDate(String base, String quote, java.time.LocalDate rateDate);
    }

    /**
     * 공개 단가 캐시 — owner가 없는 유일한 테이블
     */
    interface PriceCacheJpa extends Repository<Ec2PriceCacheEntity, UUID> {
        Ec2PriceCacheEntity save(Ec2PriceCacheEntity entity);

        Optional<Ec2PriceCacheEntity> findByRegionAndInstanceTypeAndOperatingSystemAndTenancyAndLicenseModelAndPreInstalledSw(
            String region, String instanceType, String operatingSystem, String tenancy, String licenseModel,
            String preInstalledSw);
    }
}
