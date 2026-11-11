#!/usr/bin/env bash
# 서버(EC2)에서 처음 한 번 실행: 서버 감시(sentinel)를 1분마다 도는 systemd 타이머로 등록한다.
# 사용: bash ~/cloudcost/sentinel/install.sh   (먼저 .env에 DISCORD_ALERT_WEBHOOK_URL을 넣는다. docs/DEPLOY.md 9-2)
set -euo pipefail

APP_DIR=${CCI_APP_DIR:-$HOME/cloudcost}
SCRIPT=$APP_DIR/sentinel/sentinel.sh
test -f "$SCRIPT" || { echo "$SCRIPT 가 없습니다. 먼저 배포하세요."; exit 1; }
grep -q '^DISCORD_ALERT_WEBHOOK_URL=https://' "$APP_DIR/.env" \
  || { echo "$APP_DIR/.env 에 DISCORD_ALERT_WEBHOOK_URL=https://discord.com/api/webhooks/... 를 먼저 넣으세요."; exit 1; }

sudo dnf install -y -q jq openssl >/dev/null
mkdir -p "$APP_DIR/logs/caddy"

# 컨테이너·SSH 로그·/proc를 읽어야 해서 root로 실행한다 (ec2-user는 원래 sudo가 가능하다)
sudo tee /etc/systemd/system/cci-sentinel.service >/dev/null <<EOF
[Unit]
Description=CloudCost 서버 감시 (디스코드 알림)
After=docker.service

[Service]
Type=oneshot
Environment=CCI_APP_DIR=$APP_DIR
ExecStart=/bin/bash $SCRIPT run
TimeoutStartSec=50
Nice=10
EOF

sudo tee /etc/systemd/system/cci-sentinel.timer >/dev/null <<'EOF'
[Unit]
Description=CloudCost 서버 감시 1분마다

[Timer]
OnBootSec=2min
OnUnitActiveSec=60s
AccuracySec=5s

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now cci-sentinel.timer
sudo CCI_APP_DIR="$APP_DIR" bash "$SCRIPT" hello
echo "완료. 디스코드에 '서버 감시 시작' 메시지가 왔는지 확인하세요."
echo "상태: systemctl list-timers cci-sentinel.timer  /  지금 값 보기: sudo bash $SCRIPT dry"
