#!/usr/bin/env bash
# Oracle Cloud Always Free VM(Ubuntu 22.04/24.04, Ampere A1 또는 x86)에 스택을 올린다.
# 같은 VM에서 다시 실행하면 최신 main을 받아 재빌드한다. 기존 .env와 DB 볼륨은 유지한다.
#
#   curl -fsSL https://raw.githubusercontent.com/Lcjam/CrewDeal/main/ops/oci/bootstrap.sh | bash
#   (브랜치 지정) REPO_REF=chore/oci-deploy bash bootstrap.sh
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/Lcjam/CrewDeal.git}"
REPO_REF="${REPO_REF:-main}"
APP_DIR="${APP_DIR:-$HOME/CrewDeal}"
SWAP_SIZE="${SWAP_SIZE:-4G}"

log() { echo "[bootstrap] $*"; }

# Gradle 빌드 2개 + JVM 2개 + Postgres가 메모리를 동시에 쓴다. 1GB 인스턴스에서도 빌드가 죽지 않게 스왑을 둔다.
if ! swapon --show | grep -q /swapfile; then
  log "swap ${SWAP_SIZE} 생성"
  sudo fallocate -l "${SWAP_SIZE}" /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile > /dev/null
  sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
fi

if ! command -v docker > /dev/null; then
  log "Docker 설치"
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi
command -v git > /dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq git; }

if [ -d "${APP_DIR}/.git" ]; then
  log "저장소 갱신 (${REPO_REF})"
  git -C "${APP_DIR}" fetch --quiet origin "${REPO_REF}"
  git -C "${APP_DIR}" checkout --quiet -B "${REPO_REF}" "origin/${REPO_REF}"
else
  log "저장소 클론 (${REPO_REF})"
  git clone --quiet --branch "${REPO_REF}" "${REPO_URL}" "${APP_DIR}"
fi
cd "${APP_DIR}"

if [ ! -f .env ]; then
  log ".env 생성 (무작위 비밀값)"
  rand() { openssl rand -hex 24; }
  cat > .env <<ENV
POSTGRES_PASSWORD=$(rand)
WEBHOOK_SECRET=$(rand)
GRAFANA_ADMIN_PASSWORD=$(rand)
SETTLEMENT_GRACE_PERIOD=0s
APP_BIND=0.0.0.0
ENV
  chmod 600 .env
fi

log "빌드 및 기동 (첫 빌드는 수 분 걸린다)"
sudo docker compose -f docker-compose.oci.yml --env-file .env up -d --build

log "app 헬스 대기"
for _ in $(seq 1 60); do
  if curl --fail --silent http://localhost:8080/actuator/health > /dev/null; then
    log "UP — http://<공인 IP>:8080/console/index.html"
    exit 0
  fi
  sleep 5
done
log "app이 5분 안에 UP 되지 않았다: sudo docker compose -f docker-compose.oci.yml logs app"
exit 1
