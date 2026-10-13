# ADR-003: CloudFormation 빠른 생성 링크 + SNS 신호로 연결을 자동 완료한다

- 상태: 승인됨 (2026-10-08, 사용자 확인). ADR-001의 수동 등록은 예비 경로로 남긴다.

## 배경
ADR-001 방식은 사용자가 정책 JSON 2개를 붙여 넣고 Role ARN을 복사해야 해서 번거롭다.
이메일이나 계정 ID만으로 연결하는 방법은 AWS에 없다 (계정 주인이 IAM 권한을 직접 만들어야 한다).
Datadog처럼 Lambda를 고객 계정에 만드는 방식은 고객 계정 리소스가 늘고, 우리 서버를 인터넷에 공개해야 한다.

## 결정
- `POST /aws/accounts` 응답에 CloudFormation **빠른 생성 링크**를 준다. 파라미터(External ID, 백엔드 Role, 연결 ID,
  SNS 토픽, 조회 리전)는 링크에 채워져 있고, 사용자는 체크박스 1개 + [스택 생성]만 누른다.
- 템플릿은 고객 계정에 **Read Only Role 하나**와 Custom Resource를 만든다. Custom Resource의 ServiceToken은
  **우리 계정 SNS 토픽**이라 고객 계정에 Lambda가 생기지 않는다.
- SNS → 우리 SQS 큐 → 서버가 long polling으로 꺼낸다. 서버를 공개하지 않아도 되므로 로컬에서도 동작한다.
- 신호 내용은 믿지 않는다. 연결 ID + External ID가 맞고, 실제 AssumeRole + DescribeInstances가 성공해야 ACTIVE.
  IAM 반영 지연을 고려해 10초 간격으로 최대 6번 시도한다. 실패하면 FAILED 응답 → 고객 스택 롤백(Role 삭제).
- 스택 삭제 신호를 받으면 연결을 DISCONNECTED로 바꾸고 항상 SUCCESS로 응답한다.
- CloudFormation 응답(ResponseURL PUT)은 CloudFormation 응답용 S3 호스트일 때만 보낸다 (SSRF 방지).

## 결과
- 사용자: 링크 클릭 → 체크 → 생성. 관리자: 서비스 준비 때 S3·SNS·SQS를 1번 만든다 (사용자별 작업 없음).
- 신호 처리용으로 External ID로 연결을 찾는 조회가 하나 생긴다(owner 없이). owner는 찾은 행에서 읽는다.
- 서버가 1시간 넘게 꺼져 있으면 CloudFormation이 응답을 기다리다 실패한다. 사용자는 링크를 다시 누르면 된다.
- 고객 Role 이름이 `CloudCostReadOnlyRole`로 고정되므로 계정당 연결은 하나다 (IAM Role 이름은 계정 전역).
