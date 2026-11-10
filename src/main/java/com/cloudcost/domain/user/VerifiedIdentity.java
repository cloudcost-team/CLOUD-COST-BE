package com.cloudcost.domain.user;

/**
 * 외부 로그인(Google)이 서명으로 보증한 신원. 검증을 통과한 값만 이 객체가 된다.
 *
 * @param subject 제공자 안에서 바뀌지 않는 사용자 ID (Google sub)
 * @param picture 프로필 사진 URL (없으면 null). 저장하지 않고 로그인 응답에만 담는다
 */
public record VerifiedIdentity(String provider, String subject, String email, String name, String picture) {}
