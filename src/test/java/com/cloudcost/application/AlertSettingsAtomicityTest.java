package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.NotificationService.BudgetChange;
import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 알림 설정 저장 (#35 리뷰): 조건과 연결별 예산은 함께 저장되고, 도중에 실패하면 함께 되돌린다 */
class AlertSettingsAtomicityTest extends IntegrationTestBase {

    @Autowired AwsAccountService accounts;
    @Autowired DashboardService dashboards;
    @Autowired Ec2UsageQueryService queries;
    @Autowired BudgetRepository budgets;
    @Autowired AlertSettingsRepository settings;
    @Autowired NotificationRepository notifications;
    @Autowired UnitOfWork unitOfWork;
    @Autowired CurrencyService currencies;
    @Autowired Clock clock;
    @Autowired UsageSettings usage;

    @Test
    void 예산_저장이_도중에_실패하면_조건과_앞선_예산도_저장하지_않는다() throws Exception {
        UUID first = UUID.fromString(activeAccount());
        // 같은 연결의 예산을 두 번 바꾸고, 두 번째 저장에서 확인 뒤 연결이 지워진 상황을 흉내 낸다
        DashboardService failing = spy(dashboards);
        doCallRealMethod().doThrow(new NotFoundException("NOT_FOUND", "연결을 찾을 수 없어요."))
            .when(failing).setBudget(eq(owner), eq(first), any());
        NotificationService service = new NotificationService(
            accounts, failing, queries, budgets, settings, notifications, unitOfWork, currencies, clock, usage);
        MonthlyBudget budget = new MonthlyBudget(new BigDecimal("100"), Currency.USD);
        AlertSettings off = new AlertSettings(false, false, false, false, false);

        assertThatThrownBy(() -> service.updateSettings(owner, off,
                List.of(new BudgetChange(first, budget), new BudgetChange(first, budget))))
            .isInstanceOf(NotFoundException.class);

        assertThat(settings.findByOwnerId(owner)).as("조건도 저장하지 않는다").isEmpty();
        assertThat(budgets.findByOwnerIdAndAccountId(owner, first)).as("앞선 예산도 저장하지 않는다").isEmpty();
    }
}
