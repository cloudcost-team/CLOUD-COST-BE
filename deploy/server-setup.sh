#!/usr/bin/env bash
# EC2(Amazon Linux 2023)에서 처음 한 번 실행한다: Docker, Compose, 2GB 스왑 설치.
set -euo pipefail

sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

# Docker Compose 플러그인
sudo mkdir -p /usr/local/lib/docker/cli-plugins
ARCH=$(uname -m)
sudo curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 이미지 빌드용 buildx 플러그인 (Compose가 0.17 이상을 요구한다)
case "$ARCH" in x86_64) BX_ARCH=amd64 ;; aarch64) BX_ARCH=arm64 ;; *) BX_ARCH=$ARCH ;; esac
BX_VERSION=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/docker/buildx/releases/latest)
BX_VERSION=${BX_VERSION##*/}
sudo curl -fsSL "https://github.com/docker/buildx/releases/download/${BX_VERSION}/buildx-${BX_VERSION}.linux-${BX_ARCH}" \
  -o /usr/local/lib/docker/cli-plugins/docker-buildx
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx

# 메모리 여유용 스왑 2GB
if [ ! -f /swapfile ]; then
  sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

mkdir -p ~/cloudcost
echo "완료. 한 번 로그아웃 후 다시 접속해야 docker 명령을 sudo 없이 쓸 수 있습니다."
