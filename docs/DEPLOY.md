# DEPLOY — EC2 한 대에 배포하기 (체험판)

> 구성: EC2 t3.small(Amazon Linux 2023) 안에 Docker로 **Caddy(HTTPS 자동) → CloudCost 앱 → PostgreSQL**.
> 서버는 EC2 인스턴스 역할로 AWS를 호출한다. **액세스 키를 서버에 두지 않는다.**
> 파일: `deploy/Dockerfile`, `deploy/docker-compose.yml`, `deploy/Caddyfile`, `deploy/server-setup.sh`, `deploy/deploy.sh`

## 0. 준비물
- 도메인 (가비아·Route 53 등 어디서 사도 됨). 아래에서 `<DOMAIN>`으로 쓴다
- Google OAuth 클라이언트 ID (로그인)
- 백엔드 Role `CloudCostInsightBackendRole` (docs/AWS_SETUP_GUIDE.md 3절)

## 1. 백엔드 Role을 EC2가 쓸 수 있게 하기
1. IAM → 역할 → `CloudCostInsightBackendRole` → **신뢰 관계 → 신뢰 정책 편집** → `Statement` 배열에 하나 추가:
   ```json
   { "Effect": "Allow", "Principal": { "Service": "ec2.amazonaws.com" }, "Action": "sts:AssumeRole" }
   ```
2. EC2에 붙이려면 같은 이름의 **인스턴스 프로파일**이 있어야 한다. 3절에서 역할 목록에 안 보이면 아래를 한 번 실행한다:
   ```bash
   aws iam create-instance-profile --instance-profile-name CloudCostInsightBackendRole
   aws iam add-role-to-instance-profile --instance-profile-name CloudCostInsightBackendRole --role-name CloudCostInsightBackendRole
   ```
서버가 이 역할로 고객 Role을 맡으므로, 고객 Trust Policy의 Principal(`CCI_BACKEND_ROLE_ARN`)은 바뀌지 않는다.

## 2. 키 페어
EC2 → 키 페어 → 키 페어 생성: 이름 `cci-server-key`, RSA, `.pem` → 다운로드 후
```bash
mv ~/Downloads/cci-server-key.pem ~/.ssh/ && chmod 400 ~/.ssh/cci-server-key.pem
```

## 3. EC2 인스턴스
EC2(서울) → 인스턴스 시작
- 이름 `cloudcost-server`, AMI **Amazon Linux 2023**, 유형 **t3.small** (2GB. 부족하면 중지 → 유형 변경(t3.medium) → 시작), 키 페어 `cci-server-key`
- 네트워크 → 보안 그룹 생성: **SSH(22) = 내 IP**, **HTTP(80) = 0.0.0.0/0**, **HTTPS(443) = 0.0.0.0/0**
  (80은 HTTPS 인증서 발급과 https 이동에 필요하다)
- 스토리지 **20GiB gp3**
- 고급 세부 정보 → **IAM 인스턴스 프로파일 = CloudCostInsightBackendRole**
- 시작 후: EC2 → **탄력적 IP → 할당 → 이 인스턴스에 연결** (중지·시작해도 IP가 바뀌지 않게)

## 4. 도메인 연결
도메인을 산 곳의 DNS에 A 레코드 하나만 추가한다 (Caddy·Google 로그인은 등록 업체와 무관하다).
- **가비아**: My가비아 → 도메인 [관리] → DNS 정보 → DNS 설정 → 레코드 추가: 타입 A, 호스트 `@`(또는 `app`), 값 = 탄력적 IP, TTL 600 → 저장.
  네임서버는 가비아 기본값 그대로 둔다 (Route 53 호스팅 영역 비용 없음).
- **Route 53**: 호스팅 영역 → `<DOMAIN>` → 레코드 생성: 유형 A, 레코드 이름 비움(또는 `app`), 값 = 탄력적 IP.
확인: `dig +short <DOMAIN>` 이 탄력적 IP를 돌려주면 된다 (수 분).

## 5. 서버 준비 (한 번만)
```bash
scp -i ~/.ssh/cci-server-key.pem BE/deploy/server-setup.sh ec2-user@<DOMAIN>:~
ssh -i ~/.ssh/cci-server-key.pem ec2-user@<DOMAIN> 'bash ~/server-setup.sh'
```
서버에 `~/cloudcost/.env`를 만든다 (비밀값은 서버에서 생성해 로컬·채팅에 남기지 않는다):
```bash
ssh -i ~/.ssh/cci-server-key.pem ec2-user@<DOMAIN>
cd ~/cloudcost && cat > .env <<EOF
DOMAIN=<DOMAIN>
APP_ENV=prod
AUTH_MODE=google
GOOGLE_CLIENT_ID=<Google 클라이언트 ID>
JWT_SECRET=$(openssl rand -base64 48)
DB_PASSWORD=$(openssl rand -hex 24)
CCI_BACKEND_ROLE_ARN=arn:aws:iam::<계정ID>:role/CloudCostInsightBackendRole
EOF
chmod 600 .env && exit
```

## 6. Google 로그인 허용 주소
Google Cloud → Google 인증 플랫폼 → 클라이언트 → `cloudcost-local` (또는 새 클라이언트) → **승인된 JavaScript 원본**에 `https://<DOMAIN>` 추가 → 저장 (반영 수 분).
체험판을 테스트 사용자 외에 열 때: **대상 → 앱 게시 → 프로덕션으로 푸시** (이메일·이름만 쓰므로 심사 없음).

## 7. 배포 (로컬 Mac에서, 매번)
```bash
cd BE && SERVER=ec2-user@<DOMAIN> KEY=~/.ssh/cci-server-key.pem deploy/deploy.sh
```
jar를 빌드해 서버로 복사하고 컨테이너를 다시 띄운다. 처음에는 HTTPS 인증서 발급에 1분 정도 걸린다.

## 8. 확인
- `https://<DOMAIN>/login.html` → Google 로그인 → 토큰 → `https://<DOMAIN>/swagger-ui.html` Authorize
- `POST /api/v1/aws/accounts` → AWS 연결 → `GET .../instances`에 `cloudcost-server` 자신이 보이면 성공
- 토큰 없이 API를 부르면 401이어야 한다

### 도메인을 사기 전
- **임시 주소**: 탄력적 IP가 `13.125.10.20`이면 `.env`에 `DOMAIN=13-125-10-20.sslip.io` → Caddy가 HTTPS 발급.
  Google 콘솔에 `https://13-125-10-20.sslip.io`를 추가해 보고, 거절되면 아래 터널을 쓴다.
- **SSH 터널 (나만 확인)**: `ssh -i ~/.ssh/cci-server-key.pem -N -L 8080:127.0.0.1:8080 ec2-user@<탄력적 IP>` 실행 후
  내 PC에서 `http://localhost:8080/login.html` → 서버 앱으로 Google 로그인 (Google에 `http://localhost:8080`이 등록돼 있음).
  로컬에서 띄운 앱이 8080을 쓰고 있으면 먼저 끈다.
- 도메인을 사면: DNS A 레코드 → `.env`의 `DOMAIN` 변경 → `docker compose up -d` → Google에 `https://<DOMAIN>` 추가.

## 9. 운영
| 할 일 | 명령 (서버에서, `cd ~/cloudcost`) |
|---|---|
| 앱 로그 | `docker compose logs -f app` |
| 재시작 | `docker compose restart app` |
| 상태 | `docker compose ps` |
| DB 접속 (IntelliJ 등) | SSH 터널 사용: SSH `ec2-user@<탄력적 IP>` + 키 → DB 호스트 `localhost`, 포트 `5432`, DB `cci`, 사용자 `cci`, 비밀번호는 서버 `.env`의 `DB_PASSWORD` |
| DB 백업 | `docker compose exec db pg_dump -U cci cci > backup-$(date +%F).sql` |

**비용** (서울): t3.small 약 $19/월 (t3.medium이면 약 $38) + 공인 IPv4 약 $3.6/월 + 디스크 20GiB 약 $1.8/월 + 도메인 연 1회 (Route 53으로 DNS를 관리하면 호스팅 영역 $0.5/월 추가).
데모가 끝나면 **인스턴스 중지**로 서버 비용을 멈춘다 (디스크·IP 비용은 남는다). 완전히 그만둘 때는 인스턴스 종료 + 탄력적 IP 릴리스.

## 9-1. 모니터링 (Grafana Cloud)
서버의 Alloy 컨테이너가 VM·DB·앱 지표를 1분마다 Grafana Cloud로 보낸다. 서버에 새로 여는 포트는 없다.

**한 번만: 서버 `.env`에 추가** (토큰은 Grafana Cloud → My Account → 스택 → Prometheus → Details에서 만든 `glc_...` 값. 채팅·저장소에 쓰지 않는다)
```bash
cat >> ~/cloudcost/.env <<'ENV'
COMPOSE_PROFILES=monitoring
GRAFANA_CLOUD_PROM_URL=https://prometheus-<리전>.grafana.net/api/prom/push
GRAFANA_CLOUD_PROM_USER=<Prometheus 인스턴스 ID 숫자>
GRAFANA_CLOUD_API_KEY=<토큰>
ENV
chmod 600 ~/cloudcost/.env
```
그다음 배포(`deploy.sh` 또는 CD)하면 `alloy` 컨테이너가 함께 뜬다. 확인: `docker compose ps`, `docker compose logs alloy --tail 50`.

**한 번만: Grafana Cloud 대시보드**
1. Connections → **Linux Server** 통합 → *Install* (대시보드만 설치. Alloy 설치 안내는 건너뛴다) → Dashboards의 *Linux node / overview*에서 `cloudcost-server` 선택
2. Connections → **PostgreSQL** 통합 → *Install* → *PostgreSQL overview*
3. Dashboards → New → Import → ID `4701` (JVM Micrometer) 또는 `19004` (Spring Boot 3.x Statistics), 데이터 소스는 `grafanacloud-…-prom`, `application = cloudcost-insight`
4. 알림(Alerting → Alert rules) 추천: `up{job="cloudcost-app"} == 0` 2분 / CPU 90% 10분 / 디스크 여유 15% 미만 / 메모리 여유 10% 미만

무료 플랜(활성 시계열 1만 개) 안에 들어간다. 끄려면 `.env`에서 `COMPOSE_PROFILES` 줄을 지우고 `docker compose --profile monitoring stop alloy`.

## 10. GitHub Actions CI/CD
| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `ci.yaml` | `dev`·`main`으로 가는 PR, `main` 푸시 | `./gradlew test` (실제 AWS 호출 없음) |
| `cd.yaml` | `dev` 푸시(머지) | CI 통과 → jar 빌드 → 서버 복사 → `docker compose up -d --build` → 기동 확인 |

브랜치: 기능 브랜치(`feat/#이슈번호`) → **PR to `dev`** → 머지되면 자동 배포. `main`은 안정 버전 보관용.

**서버 SSH(22)는 평소 관리자 IP만 열려 있다.** CD는 배포하는 동안만 GitHub 러너 IP를 보안 그룹에 추가하고 끝나면 지운다.
AWS 권한은 GitHub OIDC로 받는다 (GitHub에 AWS 액세스 키를 저장하지 않는다).

### 한 번만: AWS에 GitHub 배포용 역할 만들기
1. IAM → ID 제공업체 → 공급자 추가: OpenID Connect, URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`
2. 역할 `CloudCostInsightGithubDeployRole` — 신뢰 정책 (dev 브랜치 푸시만 허용):
   ```json
   { "Version": "2012-10-17", "Statement": [ { "Effect": "Allow",
     "Principal": { "Federated": "arn:aws:iam::<계정ID>:oidc-provider/token.actions.githubusercontent.com" },
     "Action": "sts:AssumeRoleWithWebIdentity",
     "Condition": {
       "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                         "token.actions.githubusercontent.com:sub": "repo:<조직>@<조직ID>/<저장소>@<저장소ID>:ref:refs/heads/dev" } } } ] }
   ```
   이 저장소는 GitHub의 **immutable subject** 형식을 쓴다. 정확한 접두어는 `gh api repos/<조직>/<저장소>/actions/oidc/customization/sub`의 `sub_claim_prefix`로 확인한다.
   (형식이 다르면 배포 단계가 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 실패한다)
   권한 정책 (서버 보안 그룹의 규칙 추가·삭제만):
   ```json
   { "Version": "2012-10-17", "Statement": [ { "Effect": "Allow",
     "Action": ["ec2:AuthorizeSecurityGroupIngress", "ec2:RevokeSecurityGroupIngress"],
     "Resource": "arn:aws:ec2:ap-northeast-2:<계정ID>:security-group/<보안 그룹 ID>" } ] }
   ```

### 한 번만: GitHub Secrets (Settings → Secrets and variables → Actions)
| 이름 | 값 |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<계정ID>:role/CloudCostInsightGithubDeployRole` |
| `PROD_SG_ID` | 서버 보안 그룹 ID (`sg-…`) |
| `PROD_HOST` | 탄력적 IP |
| `PROD_USERNAME` | `ec2-user` |
| `PROD_KEY` | 서버 SSH 비밀 키 전체 내용 (`gh secret set PROD_KEY < ~/.ssh/cci-server-key.pem`) |

## 11. 보안 체크
- [ ] 루트 사용자 액세스 키 비활성화 (IAM 대시보드 경고)
- [ ] 보안 그룹 22번은 내 IP만
- [ ] `.env`는 서버에만 (`chmod 600`), JWT_SECRET·DB_PASSWORD는 서버에서 생성
- [ ] DB 포트(5432)는 서버 내부(127.0.0.1)에만 열림, 보안 그룹에 5432 없음
- [ ] Actuator 포트(8081)도 127.0.0.1에만 열림, Caddy는 8080만 프록시
- [ ] Grafana Cloud 토큰은 서버 `.env`에만, 권한은 metrics write만
- [ ] 서버에 AWS 액세스 키 없음 (인스턴스 역할 사용)
