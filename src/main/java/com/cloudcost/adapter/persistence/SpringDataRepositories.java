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
