# CloudCost Insight — Backend

AWS 계정을 **읽기 전용 IAM Role**로 연결하면 EC2 인스턴스의 사용 시간과 **추정 비용**(사용 시간 × 온디맨드 단가)을 보여 주는 Spring Boot API 서버.

- Access Key를 받지 않는다. 고객 계정에는 `ec2:DescribeInstances`, `cloudwatch:GetMetricStatistics`만 호출한다.
- 비용은 실제 청구 금액이 아닌 `estimated_on_demand` 추정치다.
- 로그인: Google (자체 JWT 발급)

## 기술 스택
Java 21 · Spring Boot 3.5 · Spring Data JPA · Flyway · PostgreSQL · AWS SDK for Java v2 · springdoc(Swagger) · JUnit 5 · Mockito · AssertJ · ArchUnit

## 로컬 실행
```bash
docker compose up -d db
./gradlew test
AWS_PROFILE=cci-backend CCI_BACKEND_ROLE_ARN=<백엔드 Role ARN> GOOGLE_CLIENT_ID=<클라이언트 ID> ./gradlew bootRun
```
- Swagger: http://localhost:8080/swagger-ui.html
- 로그인 테스트: http://localhost:8080/login.html

## 브랜치·배포
기능 브랜치 → **PR to `dev`** (CI: `./gradlew test`) → 머지되면 **자동 배포** (CD). 자세한 내용: [docs/DEPLOY.md](docs/DEPLOY.md) 10절

## 문서
| 문서 | 내용 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 헥사고날 구조, 수집·캐시 흐름, 환경변수 |
| [docs/API.md](docs/API.md) | API 계약 |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | 테이블 |
| [docs/SECURITY.md](docs/SECURITY.md) | 보안 규칙 |
| [docs/AWS_SETUP_GUIDE.md](docs/AWS_SETUP_GUIDE.md) | AWS 계정 준비 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | EC2 배포 |
| [docs/decisions/](docs/decisions/) | ADR |
