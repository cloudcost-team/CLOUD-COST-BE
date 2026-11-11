package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.NotificationService.BudgetChange;
import com.cloudcost.application.NotificationService.BudgetRow;
import com.cloudcost.application.NotificationService.DeliveryView;
import com.cloudcost.application.NotificationService.Page;
import com.cloudcost.application.NotificationService.SettingsView;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 알림 설정 (S14) · 헤더 벨 (S14-2) · 알림 목록 (S14-6). 알림은 서비스 안에서만 보여 준다.
 */
public final class NotificationDtos {

    private NotificationDtos() {}

    public static final String CHANNEL_NOTE =
            "알림은 서비스 안 알림창(헤더 벨·알림 목록)에 보여 드리고, 등록한 Discord 채널로도 보내 드려요.";
    public static final String EVALUATION_NOTE =
            "알림 조건은 자동 수집(하루 4번) 뒤와 알림을 열어 볼 때 확인해요. 접속하지 않아도 알림이 생겨요.";

    @Schema(description = "연결 하나의 예산 바꾸기. amount가 null이면 예산을 지운다 (설정 안 함)")
    public record BudgetChangeRequest(
            @NotNull(message = "연결을 골라 주세요") UUID accountId,
            @Schema(example = "400000") BigDecimal amount,
            @Schema(example = "KRW") Currency currency) {

        BudgetChange toDomain() {
            return new BudgetChange(accountId, amount == null ? null : new MonthlyBudget(amount, currency));
        }
    }

    @Schema(description = "알림 조건과 (선택) 연결별 예산. 조건은 모두 보내야 한다")
    public record AlertSettingsRequest(
            @Schema(description = "월말 예상이 예산의 80%를 넘으면") @NotNull Boolean budget80,
            @NotNull Boolean budget100,
            @NotNull Boolean budget120,
            @Schema(description = "비용 급증일이 생기면") @NotNull Boolean spike,
            @Schema(description = "매주 월요일 이후 첫 접속 때 지난주 요약") @NotNull Boolean weeklySummary,
            @Schema(description = "바꿀 연결 예산 (선택). 없으면 예산은 그대로") @Size(max = 100)
                    List<@Valid @NotNull BudgetChangeRequest> budgets) {

        public AlertSettings conditions() {
            return new AlertSettings(budget80, budget100, budget120, spike, weeklySummary);
        }

        public List<BudgetChange> budgetChanges() {
            return budgets == null ? List.of() : budgets.stream().map(BudgetChangeRequest::toDomain).toList();
        }
    }

    @Schema(name = "AlertSettingsBudget", description = "알림 설정에 함께 보여 주는 연결별 월 예산 (입력한 통화 그대로)")
    public record BudgetDto(BigDecimal amount, String currency) {}

    public record ConnectionBudgetDto(
            UUID accountId,
            @Schema(example = "운영 서버") String name,
            @Schema(example = "ACTIVE") String status,
            @Schema(description = "정하지 않았으면 null") BudgetDto budget) {

        static ConnectionBudgetDto of(BudgetRow r) {
            return new ConnectionBudgetDto(r.account().id(), r.name(), r.account().status().name(),
                    r.budget() == null ? null : new BudgetDto(r.budget().amount(), r.budget().currency().name()));
        }
    }

    public record AlertSettingsResponse(
            boolean budget80,
            boolean budget100,
            boolean budget120,
            boolean spike,
            boolean weeklySummary,
            @Schema(example = "IN_APP") String channel,
            @Schema(description = "연결별 월 예산 (S14 예산 목록)") List<ConnectionBudgetDto> budgets,
            List<String> notes) {

        public static AlertSettingsResponse of(SettingsView v) {
            AlertSettings s = v.settings();
            return new AlertSettingsResponse(s.budget80(), s.budget100(), s.budget120(), s.spike(), s.weeklySummary(),
                    "IN_APP", v.budgets().stream().map(ConnectionBudgetDto::of).toList(),
                    List.of(CHANNEL_NOTE, EVALUATION_NOTE));
        }
    }

    public record NotificationDto(
            UUID id,
            @Schema(description = "BUDGET | SPIKE | WEEKLY_SUMMARY") String type,
            @Schema(example = "예산") String typeLabel,
            UUID accountId,
            @Schema(example = "운영 서버") String accountName,
            @Schema(example = "운영 서버의 이번 달 예상 비용이 예산의 80%를 넘었어요") String message,
            Instant createdAt,
            boolean read,
            Instant readAt,
            @Schema(description = "외부 채널별 발송 상태. 채널이 없거나 채널 등록 전 알림이면 빈 목록") List<DeliveryDto> deliveries) {

        static NotificationDto of(Notification n, String accountName, List<DeliveryView> deliveries) {
            return new NotificationDto(n.id(), n.type().name(), n.type().label(), n.accountId(), accountName,
                    n.message(), n.createdAt(), n.read(), n.readAt(),
                    deliveries == null ? List.of() : deliveries.stream().map(DeliveryDto::of).toList());
        }
    }

    @Schema(description = "알림 1건을 채널 1개로 보낸 상태")
    public record DeliveryDto(
            UUID channelId,
            @Schema(example = "DISCORD") String channelType,
            @Schema(example = "https://discord.com/api/webhooks/…7890/••••") String target,
            @Schema(description = "PENDING | SENT | FAILED") String status,
            @Schema(example = "보냄") String statusLabel,
            int attempts,
            @Schema(description = "마지막 실패 사유 (한국어). 없으면 null") String lastError,
            Instant sentAt) {

        static DeliveryDto of(DeliveryView v) {
            return new DeliveryDto(v.channel().id(), v.channel().type().name(), v.channel().maskedTarget(),
                    v.delivery().status().name(), v.delivery().status().label(), v.delivery().attempts(),
                    v.delivery().lastError(), v.delivery().sentAt());
        }
    }

    public record NotificationPageResponse(
            @Schema(description = "최신순") List<NotificationDto> items,
            @Schema(description = "다음 페이지 커서. 더 없으면 null") String nextCursor,
            long unreadCount,
            List<String> notes) {

        public static NotificationPageResponse of(Page p) {
            return new NotificationPageResponse(
                    p.items().stream().map(n -> NotificationDto.of(
                            n, p.accountNames().get(n.accountId()), p.deliveries().get(n.id()))).toList(),
                    p.nextCursor(), p.unreadCount(), List.of(CHANNEL_NOTE, EVALUATION_NOTE));
        }
    }

    public record UnreadCountResponse(@Schema(example = "3") long unreadCount) {}
}
