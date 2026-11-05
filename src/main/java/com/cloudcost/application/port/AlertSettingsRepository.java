package com.cloudcost.application.port;

import com.cloudcost.domain.alert.AlertSettings;
import java.time.Instant;
import java.util.Optional;

/** 사용자별 알림 조건. 행이 없으면 기본값(모두 켜짐)으로 본다 */
public interface AlertSettingsRepository {

    Optional<AlertSettings> findByOwnerId(String ownerId);

    void save(String ownerId, AlertSettings settings, Instant updatedAt);
}
