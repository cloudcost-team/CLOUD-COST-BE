package com.cloudcost.domain.resource;

/** 방치 리소스 규칙 (ADR-020) */
public enum ResourceWasteKind {
    UNATTACHED_VOLUME("연결 안 된 볼륨", ResourceType.VOLUME),
    UNUSED_EIP("사용하지 않는 고정 IP", ResourceType.ADDRESS),
    OLD_SNAPSHOT("오래된 스냅샷", ResourceType.SNAPSHOT),
    IDLE_LB("요청 없는 로드밸런서", ResourceType.LOAD_BALANCER),
    FORGOTTEN_REGION("잊힌 리전의 서버", ResourceType.INSTANCE);

    private final String label;
    private final ResourceType resourceType;

    ResourceWasteKind(String label, ResourceType resourceType) {
        this.label = label;
        this.resourceType = resourceType;
    }

    public String label() {
        return label;
    }

    public ResourceType resourceType() {
        return resourceType;
    }
}
