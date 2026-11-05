package com.cloudcost.domain.waste;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs.Spec;
import com.cloudcost.domain.usage.UtilizationStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 낭비 추천 1건. 금액은 USD (응답에서 환산). 문구는 화면(S07·S07-2)에 그대로 보여 줄 한국어다.
 *
 * @param monthlyHours        최근 14일 사용 시간을 월로 환산한 시간 (최대 730)
 * @param currentSpec         지금 타입의 사양. 카탈로그에 없으면 null (유휴만 해당)
 * @param monthlyCostUsd      지금 타입의 월 추정 비용 = 단가 × monthlyHours
 * @param targetSpec          추천 타입 (유휴면 null)
 * @param monthlySavingsUsd   매달 절감 예상
 */
public record WasteFinding(
    Ec2Instance instance,
    WasteKind kind,
    UtilizationStats stats,
    BigDecimal monthlyHours,
    Spec currentSpec,
    BigDecimal hourlyUsd,
    BigDecimal monthlyCostUsd,
    Spec targetSpec,
    BigDecimal targetHourlyUsd,
    BigDecimal targetMonthlyCostUsd,
    BigDecimal monthlySavingsUsd) {

    /** 시뮬레이터·벤치마킹이 같은 추천을 가리킬 때 쓰는 결정적 id */
    public String recommendationId() {
        return instance.instanceId() + ":" + kind.name();
    }

    public String displayName() {
        return instance.name() == null || instance.name().isBlank() ? instance.instanceId() : instance.name();
    }

    /** 예: "최근 14일 CPU 평균 6% (P95 9%)" */
    public String evidence() {
        if (kind == WasteKind.IDLE) {
            return "최근 14일 CPU 1% 미만 · 사용 흔적 거의 없음";
        }
        return "최근 14일 CPU 평균 " + percent(stats.cpuAvg()) + " (P95 " + percent(stats.cpuP95()) + ")";
    }

    /** 예: "m5.large → t3.medium 으로 변경 검토" */
    public String action() {
        if (kind == WasteKind.IDLE) {
            return "쓰지 않는다면 AWS 콘솔에서 중지 검토";
        }
        return instance.instanceType() + " → " + targetSpec.instanceType() + " 으로 변경 검토";
    }

    /** S07-2 "바꾸기 전에 확인해 주세요" */
    public List<String> cautions() {
        if (kind == WasteKind.IDLE) {
            return List.of(
                "다른 서버나 예약 작업이 이 서버를 가끔 쓰는지 담당자에게 먼저 확인해 주세요.",
                "중지해도 EBS 볼륨 비용은 계속 나가요 (이 추정 비용에는 EBS가 들어 있지 않아요).",
                "판단 근거는 CloudWatch 기본 지표(CPU·네트워크)뿐이에요.");
        }
        return List.of(
            "메모리 사용률은 CloudWatch 기본 지표에 없어 판단에 넣지 않았어요. 서버 안에서 메모리를 먼저 확인해 주세요.",
            "t3는 CPU 크레딧 방식이라 순간적으로 많이 쓰는 작업이 길면 느려질 수 있어요.",
            "P95는 1시간 평균값 기준이라 1분 단위로 잠깐 튀는 사용량은 반영되지 않아요.");
    }

    /** S07-2 "AWS 콘솔에서 직접 바꾸는 방법" */
    public List<String> consoleSteps() {
        String name = displayName();
        if (kind == WasteKind.IDLE) {
            return List.of(
                "EC2 → 인스턴스에서 " + name + "을(를) 골라요",
                "인스턴스 상태 → 인스턴스 중지를 눌러요 (다시 쓰려면 인스턴스 시작)");
        }
        return List.of(
            "EC2 → 인스턴스에서 " + name + "을(를) 중지해요 (중지하는 동안 서비스가 멈춰요)",
            "작업 → 인스턴스 설정 → 인스턴스 유형 변경에서 " + targetSpec.instanceType() + "을(를) 골라요",
            "인스턴스를 다시 시작해요");
    }

    /** 1% 미만은 "1% 미만", 그 밖은 반올림한 정수 % */
    static String percent(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        if (value.compareTo(BigDecimal.ONE) < 0) {
            return "1% 미만";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
