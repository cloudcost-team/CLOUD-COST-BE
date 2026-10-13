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
| DB 백업 | `docker compose exec db pg_dump -U cci cci > backup-$(date +%F).sql` |

**비용** (서울): t3.small 약 $19/월 (t3.medium이면 약 $38) + 공인 IPv4 약 $3.6/월 + 디스크 20GiB 약 $1.8/월 + 도메인 연 1회 (Route 53으로 DNS를 관리하면 호스팅 영역 $0.5/월 추가).
데모가 끝나면 **인스턴스 중지**로 서버 비용을 멈춘다 (디스크·IP 비용은 남는다). 완전히 그만둘 때는 인스턴스 종료 + 탄력적 IP 릴리스.

## 10. 보안 체크
- [ ] 루트 사용자 액세스 키 비활성화 (IAM 대시보드 경고)
- [ ] 보안 그룹 22번은 내 IP만
- [ ] `.env`는 서버에만 (`chmod 600`), JWT_SECRET·DB_PASSWORD는 서버에서 생성
- [ ] DB 포트(5432)는 밖으로 열지 않음 (compose에 ports 없음)
- [ ] 서버에 AWS 액세스 키 없음 (인스턴스 역할 사용)
