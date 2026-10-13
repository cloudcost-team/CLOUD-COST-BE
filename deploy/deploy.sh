#!/usr/bin/env bash
# 로컬(Mac)에서 실행: jar 빌드 → 서버로 복사 → 서버에서 컨테이너 재시작.
# 사용: SERVER=ec2-user@<도메인 또는 IP> KEY=~/.ssh/cci-server-key.pem deploy/deploy.sh
set -euo pipefail

: "${SERVER:?SERVER=ec2-user@<host> 가 필요합니다}"
: "${KEY:?KEY=<pem 파일 경로> 가 필요합니다}"
cd "$(dirname "$0")/.."

./gradlew clean bootJar -x test -q
cp build/libs/cloudcost-insight-*.jar deploy/app.jar

SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=accept-new)
scp "${SSH_OPTS[@]}" deploy/app.jar deploy/Dockerfile deploy/docker-compose.yml deploy/Caddyfile "$SERVER:~/cloudcost/"
rm -f deploy/app.jar

ssh "${SSH_OPTS[@]}" "$SERVER" 'cd ~/cloudcost && test -f .env || { echo ".env가 없습니다. docs/DEPLOY.md 5절을 보세요."; exit 1; } && docker compose up -d --build && docker compose ps'
echo "배포 완료. 로그: ssh -i $KEY $SERVER 'cd ~/cloudcost && docker compose logs -f app'"
