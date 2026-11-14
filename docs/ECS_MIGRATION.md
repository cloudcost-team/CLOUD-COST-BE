# EC2 → ECS 전환 (한 달 운영)

> 2026-11-15 전환 완료. 아래는 실제로 한 순서다 (기존 데이터 유지).

지금 EC2 한 대(Docker Compose + Caddy + PostgreSQL 컨테이너)에서 **VPC · ALB · ECS · RDS** 구조로 옮겨 한 달 동안 운영하고,
끝나면 `terraform destroy`로 모두 지운 뒤 EC2로 되돌린다. 근거: [ADR-021](decisions/021-multi-instance-on-ecs.md).

- 운영 DB는 3단계로 기존 EC2 데이터를 그대로 옮겼다. 옮기지 않으면 Flyway가 빈 RDS에 테이블을 새로 만든다.
- 비용은 AWS 크레딧 안에서 쓴다. 예상 월 ~$70: ALB ~$18, RDS t4g.micro ~$21, t3.small ~$19, 공인 IPv4 ~$11, 로그 등.
  NAT Gateway는 쓰지 않는다 (ECS용 EC2가 Public 서브넷에서 공인 IP로 외부 API를 부른다. 들어오는 요청은 ALB만).
- 바뀌지 않는 것: 백엔드 도메인, Vercel(FE), Google 로그인 설정, 고객 연결용 백엔드 Role.

## 구성 (`infra/ecs`)
| 파일 | 내용 |
|---|---|
| `network.tf` | VPC 10.20.0.0/16, AZ 2개, Public 서브넷 2개(ALB·ECS용 EC2), Private 서브넷 2개(RDS), 보안 그룹 |
| `alb.tf` | ALB, ACM 인증서(DNS 검증), 80→443 이동, Target Group(`/actuator/health`), `/actuator/*` 외부 차단 |
| `ecs.tf` | ECS 클러스터, t3.small 1대(Auto Scaling 그룹), 태스크 정의(Spring Boot + Alloy 사이드카), 서비스(컨테이너 2개) |
| `rds.tf` | PostgreSQL 16, db.t4g.micro, 20GB, AZ 1개. 비밀번호는 RDS가 만들어 Secrets Manager에 둔다 (교체 주기 90일) |
| `secrets.tf` | 앱 비밀값 SSM 파라미터의 ARN만 만든다 (파라미터는 사람이 직접 만든다 — tfstate에 평문이 남지 않게) |
| `ecr.tf` | 이미지 저장소 (태그 없는 이미지는 하루 뒤 삭제, 태그 이미지는 최근 30개 보관) |
| `github.tf` | 기존 GitHub 배포 Role에 ECR push·ECS 배포 권한 추가 |
| `budget.tf` | 월 예산 알림 (기본 $70, 80% 실제·100% 예상 시 메일) |

## 1단계 — 준비 (사람)
1. Terraform 설치: `brew install terraform`. AWS 관리자 권한 프로필로 로그인한다 (`aws sts get-caller-identity`로 확인).
2. 백엔드 Role 신뢰 정책에 ECS 추가 — [DEPLOY.md 1절](DEPLOY.md) 마지막 문단 (`ecs-tasks.amazonaws.com`). **새 Role을 만들지 않는다.**
3. `infra/ecs/terraform.tfvars.example`을 `terraform.tfvars`로 복사해 채운다. 비밀이 아닌 값만 넣는다
   (도메인, 예산 메일, Google 클라이언트 ID, 지금 서버 `.env`의 템플릿 주소·SNS·SQS 주소, Grafana 주소·사용자).
4. **비밀값 파라미터를 직접 만든다** (Terraform이 만들면 값이 tfstate에 평문으로 남는다).
   값을 명령줄에 쓰지 않는다 (셸 기록에 남는다). `read -rs`로 입력받아 넘긴다. 한 줄씩 실행하고, 붙여 넣은 뒤 엔터:
```bash
read -rs V && aws ssm put-parameter --overwrite --type SecureString --name /cci/google-client-secret --value "$V"; unset V
```
```bash
read -rs V && aws ssm put-parameter --overwrite --type SecureString --name /cci/grafana-api-key --value "$V"; unset V
```
   JWT·암호화 키는 **지금 서버 `.env`의 `JWT_SECRET`·`CCI_SECRET_KEY`를 그대로** 넣는다
   (암호화된 Discord 웹훅을 풀고 로그인을 유지하려면 같은 키여야 한다). 새로 시작한다면 `openssl rand -base64 48`/`32`로 만든다.
```bash
read -rs V && aws ssm put-parameter --overwrite --type SecureString --name /cci/jwt-secret --value "$V"; unset V
```
```bash
read -rs V && aws ssm put-parameter --overwrite --type SecureString --name /cci/secret-key --value "$V"; unset V
```
   DB 비밀번호는 넣지 않는다 (RDS가 만들어 Secrets Manager에 두고, 태스크가 바로 읽는다).

## 2단계 — 인프라 만들기
Terraform이 없으면 Docker로 같은 명령을 쓴다: `docker run --rm -it -v "$PWD/../..":/w -v "$HOME/.aws":/root/.aws:ro -w /w/infra/ecs hashicorp/terraform:1.9.8 <명령>`
```bash
cd infra/ecs
terraform init
terraform plan
```
```bash
terraform apply
```
`apply`가 ACM 인증서 확인에서 멈춘다. 다른 터미널에서 확인 레코드를 본다
(출력이 비어 있으면 AWS 콘솔 → Certificate Manager → 인증서 상세의 "도메인" 표에서 같은 CNAME을 본다):
```bash
aws acm list-certificates --query "CertificateSummaryList[].CertificateArn"
```
```bash
aws acm describe-certificate --certificate-arn <위 ARN> --query "Certificate.DomainValidationOptions[0].ResourceRecord"
```
도메인 업체(가비아 등) DNS에 그 **CNAME 레코드를 추가**하면 몇 분 안에 `apply`가 이어서 끝난다.
이때 ECS 서비스는 이미지가 아직 없어 태스크가 뜨지 않는다 (4단계 첫 배포 후 정상).

## 3단계 — 기존 데이터 옮기기 (선택, 시연 데이터를 유지할 때)
EC2 DB를 떠서 RDS에 그대로 넣는다. RDS는 Private 서브넷이라, ECS 호스트에서 한 번만 도는 복원 태스크로 넣는다.
1. ECS 서비스 컨테이너 수를 0으로 내린다 (복원 중 DB를 쓰지 않게).
2. EC2에서 `docker compose exec -T db pg_dump -U cci -d cci -Fc > /tmp/cci.dump` → 로컬로 복사 →
   비공개 S3 버킷에 올리고 15분짜리 presigned URL을 만든다.
3. `postgres:16-alpine` 이미지로 복원 태스크를 띄운다 (DB 비밀번호는 Secrets Manager에서 주입, URL은 실행할 때 넘김):
   `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` → `pg_restore --no-owner --no-privileges`.
4. 컨테이너 수를 2로 올린다. Flyway가 남은 마이그레이션(V14)만 적용한다.
5. 덤프는 서버·로컬·S3에서 모두 지운다 (개인정보).

## 4단계 — 첫 배포
1. GitHub → Settings → Secrets and variables → Actions → **Variables** → `DEPLOY_TARGET` = `ecs`.
2. ECS 배포 잡이 들어 있는 브랜치를 `dev`에 머지한다 (변수가 없으면 지금처럼 EC2로 배포된다).
   이미지가 ECR에 올라가고 ECS가 컨테이너 2개를 띄운다.
4. 확인 (도메인 전환 전, 인증서 이름이 달라 경고는 무시):
```bash
curl -sk -H "Host: <백엔드 도메인>" https://$(terraform output -raw alb_dns_name)/api/v1/auth/config
```

## 5단계 — 도메인 전환
1. 도메인 업체 DNS에서 백엔드 도메인의 **A 레코드(EC2 IP)를 지우고 CNAME = `alb_dns_name` 값**으로 바꾼다.
   루트 도메인(`@`)은 CNAME이 안 되는 업체가 많다. 백엔드는 서브도메인을 쓴다.
2. `dig +short <백엔드 도메인> @8.8.8.8`(공용 DNS)이 ALB 주소를 돌려주고, 브라우저 자물쇠 발급자가 **Amazon**이면 전환 완료.
3. **이전 A 레코드의 TTL(가비아 기본 600초)이 지난 뒤** EC2 앱을 끈다. 그 전에는 캐시된 옛 주소로 오는 요청이 있다.
   두 서버가 같은 SQS 메시지를 나눠 받거나 Discord 알림을 두 번 보내지 않게 앱·Caddy·Alloy·Sentinel을 끈다
   (`docker compose stop app alloy caddy`, `sudo systemctl stop cci-sentinel.timer`). DB는 되돌리기용으로 남긴다.
4. 하루 정도 문제가 없으면 EC2 인스턴스를 **중지**한다 (지우지 않는다). 중지 중에는 디스크·고정 IP 비용만 든다.
4. Grafana Cloud 경보 `up{job="cloudcost-app"} == 0`이 그대로 동작한다 (ECS에는 Sentinel이 없다. 장애 알림은 Grafana가 맡는다).

## 운영 중
- 배포: 지금처럼 `dev`에 머지하면 된다. 컨테이너를 하나씩 바꾸고, 새 버전이 헬스체크를 못 넘으면 ECS가 이전 버전으로 되돌린다.
- 로그: CloudWatch Logs `/ecs/cci-app`. 서버 접속이 필요하면 SSH 대신 Systems Manager → Session Manager.
- 예산 메일이 오면 [정리](#한-달-뒤--정리)를 앞당긴다.

## 한 달 뒤 — 정리
1. 기존 EC2를 **시작**하고 DNS를 다시 **A 레코드 = EC2 고정 IP**로 바꾼다 (EC2 DB는 전환 전 데이터 그대로다).
2. GitHub 변수 `DEPLOY_TARGET`을 `ec2`로 바꾼다 (또는 지운다).
3. 인프라를 지운다. RDS 데이터도 함께 지워진다 (저장소 루트에서):
```bash
terraform -chdir=infra/ecs destroy
```
4. 남은 것 확인: 결제 대시보드에서 다음 날 ECS·RDS·ELB 요금이 0인지 본다. ACM 확인용 CNAME은 지워도 된다.
