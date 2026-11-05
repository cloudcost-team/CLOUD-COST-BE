package com.cloudcost.domain.waste;

public enum WasteKind {
    OVERSIZED("과다 사양"),
    IDLE("유휴");

    private final String label;

    WasteKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
