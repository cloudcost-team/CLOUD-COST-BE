# ADR-020: 읽기 권한·리전 확장 — 방치 리소스와 잊힌 리전 (ADR-009·010 보완)

- 상태: 채택 (2026-11-12, 아키텍처 변경안 "변경 2", 담당자 승인)

## 배경
- 고객 Role이 `ec2:DescribeInstances`·`cloudwatch:GetMetricStatistics` 두 권한과 `aws:RequestedRegion` 한 곳만 허용해,
  기획 원안의 핵심이던 미연결 볼륨·고정 IP·스냅샷 탐지가 빠졌고, 다른 리전에 켜 둔 서버도 볼 수 없었다.
- 남은 낭비 탐지(서버 사양·유휴)는 Compute Optimizer와 겹쳐 보여 차별점이 약하다.

## 결정
- **권한 5개 추가 (모두 읽기, 와일드카드 없음)**: `ec2:DescribeRegions`, `ec2:DescribeVolumes`, `ec2:DescribeAddresses`,
  `ec2:DescribeSnapshots`(항상 `OwnerIds=self`), `elasticloadbalancing:DescribeLoadBalancers`. 쓰기 권한은 여전히 없다.
- **리전 조건 제거**: 잊힌 리전을 찾으려면 모든 리전을 읽어야 한다. 사용량(CloudWatch 시간별)은 여전히 연결한 기본 리전만 수집하고,
  다른 리전에서는 Describe와 LB 요청 수(LB당 GetMetricStatistics 1건, ALB는 요청이 0이면 거부 요청 지표까지 최대 2건, 1일 단위 14개)만 부른다.
- **허용 목록 3곳 동시 수정**: `scripts/customer-role-allowlist.txt`, `CustomerRolePolicy.ALLOWED_ACTIONS`, ArchUnit 허용 메서드 + 템플릿 테스트.
- **확장 수집**: `ResourceScanService`가 켜진 리전을 6곳씩 동시에 훑어 `account_resources`에 통째로 바꿔 저장한다(6시간 캐시).
  예약 수집(ADR-019) 뒤에도 돈다. 화면 조회는 수집을 기다리지 않고 백그라운드(2개 연결 동시)에 맡긴 뒤 저장된 결과를 준다(`refreshing`).
  마지막 수집 1회에서 시도한 Describe·GetMetricStatistics 수(실패 포함)를 `account_resource_scans`에 남긴다(누적 아님). 공인 IP 주소·태그 전체는 저장하지 않는다(고정 IP는 allocation id만).
- **기존 연결(v1 템플릿)**: 새 액션이 AccessDenied면 연결 오류로 만들지 않고 `account_resource_scans.status = PERMISSION_UPGRADE_REQUIRED`,
  `permission_version = 1`(확인 안 됨)로 남긴다. 거부만으로는 v1인지 SCP·권한 경계인지 알 수 없어 안내문에 둘 다 적는다.
  스택 연결이면 저장된 스택 ARN의 리전·이름으로 스택 업데이트 방법을, 직접 만든 Role이면 IAM 콘솔 링크와 정책 JSON을 준다. 1시간마다 다시 확인.
  (`aws_accounts`에 컬럼을 두지 않고 별도 표에 둔 것은 연결 상태 전이(`AwsAccount`)와 섞지 않기 위해서다.)
- **규칙**: UNATTACHED_VOLUME(available), UNUSED_EIP(연결 없음), OLD_SNAPSHOT(90일 초과), IDLE_LB(만든 지 14일 넘고 14일 요청 0 —
  ALB RequestCount가 0이면 거부한 요청 HTTPCode_ELB_5XX_Count도 확인, NLB NewFlowCount. 조회 끝은 지금 시각), FORGOTTEN_REGION(기본 리전 밖에서 실행 중인 서버).
- **금액**: 볼륨·스냅샷·고정 IP·LB는 공개 정가 표(`ResourcePrices`, 서울·버지니아, 그 밖은 버지니아로 추정)로 계산한 월 추정치,
  `costBasis = estimated_list_price`. 단가를 모르는 항목이 있으면 합계는 null. 스냅샷은 원본 크기 기준 최대치, LB는 LCU 제외. 잊힌 리전 서버는 Price List 온디맨드 단가 × 730.
  가격 값은 공식 가격 페이지로 다시 확인한다.

## 결과
- 낭비 목록(`GET /waste`)에 `resources`가 붙고, `GET /resources/waste`로 따로도 본다. 체험 모드에도 예시가 있다.
- 고객 CloudTrail에 Describe 호출이 리전 수만큼 기록된다 (추가 Trail을 쓰면 10만 건당 $2 정도가 들 수 있어 화면에 안내).
- EC2 사용량 추정 비용(`estimated_on_demand`)에는 여전히 EBS·고정 IP가 들어가지 않는다. 벤치마크 "전체 낭비 비율"은 후속 작업.
