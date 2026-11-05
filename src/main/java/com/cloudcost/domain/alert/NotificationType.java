package com.cloudcost.domain.alert;

public enum NotificationType {
    BUDGET("예산"),
    SPIKE("비용 급증"),
    WEEKLY_SUMMARY("주간 요약");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
