package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;

/**
 * 사용자가 연결에 붙이는 이름 (예: "개인 계정", "캡스톤 팀"). 화면 표시용이라 AWS에는 보내지 않는다.
 * 앞뒤 공백을 지우고, 비어 있으면 이름이 없는 것(null)으로 본다.
 */
public final class ConnectionAlias {

    public static final int MAX_LENGTH = 40;

    private ConnectionAlias() {
    }

    /** @return 정리한 이름, 비어 있으면 null */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw new DomainValidationException(
                "INVALID_ALIAS", "연결 이름은 " + MAX_LENGTH + "자 이하로 입력하세요.");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                "INVALID_ALIAS", "연결 이름에 줄바꿈이나 제어 문자를 넣을 수 없습니다.");
        }
        return trimmed;
    }
}
