package com.cloudcost.domain.resource;

/** EC2 사용량 밖에서 찾는 리소스 종류 (ADR-020) */
public enum ResourceType {
    INSTANCE("서버"),
    VOLUME("볼륨"),
    ADDRESS("고정 IP"),
    SNAPSHOT("스냅샷"),
    LOAD_BALANCER("로드밸런서");

    private final String label;

    ResourceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
