package com.cloudcost.domain.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ② 전체 낭비 비율 (기능명세서 F07-2, ADR-020). 금액은 USD다.
 *
 * <ul>
 *   <li>낭비 금액 = 서버 낭비(과다 사양·유휴 추천의 월 절감액 합) + 방치 리소스 월 비용(볼륨·고정 IP·스냅샷·LB·잊힌 리전 서버)
 *   <li>전체 월 비용 = 월말 예상(EC2 온디맨드 추정) + 방치 리소스 월 비용.
 *       월말 예상에는 EBS·고정 IP·LB가 들어가지 않으므로 방치 리소스 비용을 분모에도 더한다 (빼면 100%를 넘을 수 있다)
 *   <li>비율 = 낭비 금액 ÷ 전체 월 비용 × 100, 소수 첫째 자리. 100%를 넘지 않는다
 * </ul>
 * 사용 중인 볼륨·LB 비용은 모르므로 분모에 넣지 않는다 (그만큼 실제보다 비율이 높게 나올 수 있다 — 응답에 안내한다).
 *
 * @param serverWasteUsd   서버 낭비 월 절감액 합
 * @param resourceWasteUsd 방치 리소스 월 비용 합
 * @param monthEndUsd      월말 예상 (EC2)
 * @param ratioPercent     비율 (%). 전체 월 비용이 0이면 null
 */
public record WasteRatio(
    BigDecimal serverWasteUsd, BigDecimal resourceWasteUsd, BigDecimal monthEndUsd, BigDecimal ratioPercent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 비교 자료 평균보다 낮음 · 같음 · 높음 */
    public enum Comparison {
        BELOW,
        SAME,
        ABOVE
    }

    public static WasteRatio of(BigDecimal serverWasteUsd, BigDecimal resourceWasteUsd, BigDecimal monthEndUsd) {
        BigDecimal server = nonNegative(serverWasteUsd);
        BigDecimal resource = nonNegative(resourceWasteUsd);
        BigDecimal monthEnd = nonNegative(monthEndUsd);
        BigDecimal total = monthEnd.add(resource);
        BigDecimal ratio = null;
        if (total.signum() > 0) {
            ratio = server.add(resource).multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP).min(HUNDRED);
        }
        return new WasteRatio(server, resource, monthEnd, ratio);
    }

    public BigDecimal wasteUsd() {
        return serverWasteUsd.add(resourceWasteUsd);
    }

    public BigDecimal totalUsd() {
        return monthEndUsd.add(resourceWasteUsd);
    }

    /** @return 비율이나 평균을 모르면 null */
    public Comparison compareTo(BigDecimal averagePercent) {
        if (ratioPercent == null || averagePercent == null) {
            return null;
        }
        int c = ratioPercent.compareTo(averagePercent);
        return c < 0 ? Comparison.BELOW : c == 0 ? Comparison.SAME : Comparison.ABOVE;
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
