# ADR-001: AWS 연결을 2단계로 나눈다 (External ID 발급 → Role ARN 등록)

- 상태: 승인됨 (2026-10-08, 사용자 확인)

## 배경
기획서는 `POST /api/v1/aws/accounts {roleArn, region}` 한 번에 "External ID 생성 + AssumeRole"을 하도록 적었다.
그런데 External ID는 고객 Role의 Trust Policy(`sts:ExternalId` 조건)에 **미리** 들어가 있어야 AssumeRole이 성공한다.
서버가 요청 시점에 새로 만든 External ID로는 한 번의 호출로 성공할 수 없다.

## 결정
1. `POST /api/v1/aws/accounts {region}` → 서버가 `SecureRandom`으로 External ID(`cci-` + hex 24자)를 만들고,
   고객이 IAM에 그대로 붙여 넣을 Trust Policy와 권한 정책 JSON을 돌려준다. 상태 `PENDING`.
2. `PUT /api/v1/aws/accounts/{accountId}/role {roleArn}` → 그 연결의 External ID로 실제 STS AssumeRole +
   DescribeInstances 1건을 호출해 성공하면 `ACTIVE`, 실패하면 `ERROR`와 원인을 기록한다.

## 결과
- 사용자는 External ID를 입력하지 않는다(서버가 정책 JSON에 넣어 준다). Mock 성공은 없다.
- Swagger에서 ① → (AWS 콘솔 작업) → ② 순서로 테스트한다.
- 한 연결의 정책을 다른 연결에 재사용하면 External ID가 달라 AssumeRole이 실패한다 (의도된 동작).
