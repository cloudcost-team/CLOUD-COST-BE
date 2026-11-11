package com.cloudcost.domain.alert;

/** 외부 알림 채널 종류. 지금은 Discord 웹훅만 (ADR-019). */
public enum ChannelType {
    DISCORD("Discord");

    private final String label;

    ChannelType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
