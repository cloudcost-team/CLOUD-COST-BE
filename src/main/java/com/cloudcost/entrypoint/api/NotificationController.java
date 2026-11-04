package com.cloudcost.entrypoint.api;

import com.cloudcost.application.NotificationService;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.NotificationDtos.AlertSettingsRequest;
import com.cloudcost.entrypoint.api.dto.NotificationDtos.AlertSettingsResponse;
import com.cloudcost.entrypoint.api.dto.NotificationDtos.NotificationPageResponse;
import com.cloudcost.entrypoint.api.dto.NotificationDtos.UnreadCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "5. 알림 (서비스 안)",
    description = "예산 80·100·120%, 비용 급증, 주간 요약을 헤더 벨·알림 목록으로 보여 준다 (S14). 이메일·메신저·푸시는 보내지 않는다. "
        + "조건은 알림 목록·안 읽은 수를 조회할 때 확인한다 (예약 수집 없음).")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/settings/alerts")
    @Operation(summary = "알림 설정 보기 (S14): 조건 on/off + 연결별 월 예산")
    AlertSettingsResponse settings(@CurrentOwner String owner) {
        return AlertSettingsResponse.of(notifications.settings(owner));
    }

    @PutMapping("/settings/alerts")
    @Operation(summary = "알림 설정 저장 (S14)",
        description = "조건 5개는 모두 보낸다. budgets(선택)에 연결별 예산을 함께 바꿀 수 있다 (amount null이면 지움). "
            + "남의 연결이 섞여 있으면 아무것도 바꾸지 않고 404.")
    AlertSettingsResponse updateSettings(@CurrentOwner String owner, @Valid @RequestBody AlertSettingsRequest request) {
        return AlertSettingsResponse.of(
            notifications.updateSettings(owner, request.conditions(), request.budgetChanges()));
    }

    @GetMapping("/notifications")
    @Operation(summary = "알림 목록 (S14-6, 최신순)", description = "조회할 때 연결마다 알림 조건을 확인해 새 알림을 만든다.")
    NotificationPageResponse list(
            @CurrentOwner String owner,
            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "1~50, 기본 20") @RequestParam(required = false) Integer limit) {
        return NotificationPageResponse.of(notifications.list(owner, cursor, limit));
    }

    @GetMapping("/notifications/unread-count")
    @Operation(summary = "안 읽은 알림 수 (S14-2 헤더 벨)", description = "조회할 때 알림 조건을 확인한다.")
    UnreadCountResponse unreadCount(@CurrentOwner String owner) {
        return new UnreadCountResponse(notifications.unreadCount(owner));
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "모두 읽음")
    UnreadCountResponse readAll(@CurrentOwner String owner) {
        return new UnreadCountResponse(notifications.readAll(owner));
    }

    @PostMapping("/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "하나 읽음", description = "남의 알림이거나 없으면 404 NOTIFICATION_NOT_FOUND.")
    void read(@CurrentOwner String owner, @PathVariable UUID notificationId) {
        notifications.read(owner, notificationId);
    }
}
