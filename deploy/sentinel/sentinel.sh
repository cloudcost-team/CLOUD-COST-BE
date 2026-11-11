#!/usr/bin/env bash
# CloudCost 서버 감시 (sentinel)
# systemd 타이머가 1분마다 실행한다. 검사만 하고, 이상이 있을 때만 디스코드 웹훅으로 알린다.
#   - 같은 문제가 계속되면 ALERT_COOLDOWN(기본 10분)마다 한 번만 다시 알린다
#   - 문제가 풀리면 "✅ 복구"를 한 번 보낸다
#   - 배포 중 재시작처럼 한 번 실패하고 마는 건 무시한다 (연속 실패일 때만 알림)
#
# 사용: sentinel.sh          검사 1회 (타이머가 부름)
#       sentinel.sh test     디스코드로 테스트 메시지 1개
#       sentinel.sh dry      검사만 하고 보낼 내용을 화면에 출력 (디스코드로 안 보냄)
#
# 설정: ~/cloudcost/.env 의 DISCORD_ALERT_WEBHOOK_URL (필수), DISCORD_ALERT_MENTION, ALERT_* 임계값 (docs/DEPLOY.md 9-2)
set -uo pipefail

APP_DIR=${CCI_APP_DIR:-/home/ec2-user/cloudcost}
ENV_FILE=${CCI_ENV_FILE:-$APP_DIR/.env}
STATE_DIR=${CCI_SENTINEL_STATE:-/var/lib/cci-sentinel}
ACCESS_LOG=${CCI_ACCESS_LOG:-$APP_DIR/logs/caddy/access.log}
COMPOSE_PROJECT=cloudcost-insight-prod
MODE=${1:-run}
NOW=$(date +%s)

# .env에서 값 하나만 읽는다 (파일 전체를 source 하지 않는다). 환경변수가 있으면 그게 우선.
cfg() {
  local name=$1 def=${2:-} v
  v=${!name:-}
  if [ -z "$v" ] && [ -f "$ENV_FILE" ]; then
    v=$(grep -E "^${name}=" "$ENV_FILE" | tail -n1 | cut -d= -f2-)
    v=${v%\"}; v=${v#\"}; v=${v%\'}; v=${v#\'}
  fi
  printf '%s' "${v:-$def}"
}

WEBHOOK=$(cfg DISCORD_ALERT_WEBHOOK_URL)
MENTION=$(cfg DISCORD_ALERT_MENTION)          # 예: @here — 🔴 심각 알림에만 붙는다
DOMAIN=$(cfg DOMAIN)
COOLDOWN=$(cfg ALERT_COOLDOWN 600)
TOTAL_RPM=$(cfg ALERT_TOTAL_RPM 3000)          # 전체 분당 요청 수
IP_RPM=$(cfg ALERT_IP_RPM 600)                 # 한 IP 분당 요청 수
ERR5XX_PM=$(cfg ALERT_5XX_PM 10)               # 분당 5xx 수
AUTHFAIL_PM=$(cfg ALERT_AUTHFAIL_PM 60)        # 분당 401/403 수
PROBE_PM=$(cfg ALERT_PROBE_PM 20)              # 분당 취약점 스캔 경로 요청 수
SLOW_P95=$(cfg ALERT_SLOW_P95_SEC 3)           # 응답 시간 p95(초)
SSH_FAIL=$(cfg ALERT_SSH_FAIL 3)               # 분당 SSH 로그인 실패 수
APPERR_PM=$(cfg ALERT_APP_ERROR_PM 5)          # 분당 앱 ERROR 로그 수
CPU_PCT=$(cfg ALERT_CPU_PCT 90)
MEM_PCT=$(cfg ALERT_MEM_PCT 90)
DISK_PCT=$(cfg ALERT_DISK_PCT 85)

mkdir -p "$STATE_DIR"/{firing,cooldown,streak}
exec 9>"$STATE_DIR/lock"
if [ "$MODE" = run ]; then
  flock -n 9 || exit 0   # 타이머 실행끼리는 겹치면 건너뛴다
else
  # test·hello·dry는 사람이 직접 부른 것이라 건너뛰지 않고 타이머 실행이 끝나길 기다린다
  flock -w 60 9 || { echo "다른 검사가 60초 넘게 실행 중입니다. 잠시 뒤 다시 실행하세요." >&2; exit 1; }
fi
NOW=$(date +%s)   # 잠금을 기다린 시간만큼 '최근 1분' 기준이 밀리지 않게 다시 잰다

# ── 디스코드 전송 ───────────────────────────────────────────────
# send <crit|warn|info|ok> <제목> <내용>
send() {
  local level=$1 title=$2 desc=$3 color content="" code rc
  case $level in
    crit) color=15158332; title="🔴 $title"; content=$MENTION ;;
    warn) color=15965202; title="🟠 $title" ;;
    info) color=3447003;  title="🔵 $title" ;;
    ok)   color=3066993 ;;
  esac
  desc=${desc:0:3500}
  if [ "$MODE" = dry ]; then
    printf '\n[%s] %s\n%s\n' "$level" "$title" "$desc"
    return 0
  fi
  [ -n "$WEBHOOK" ] || { echo "DISCORD_ALERT_WEBHOOK_URL이 없습니다 ($ENV_FILE)" >&2; return 1; }
  code=$(jq -n --arg c "$content" --arg t "$title" --arg d "$desc" --argjson col "$color" \
        --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg host "${DOMAIN:-cloudcost-server}" \
    '{content: $c, embeds: [{title: $t, description: $d, color: $col,
       footer: {text: ("☁️ " + $host + " · sentinel")}, timestamp: $ts}]}' \
  | curl -sS -m 10 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d @- "$WEBHOOK")
  rc=$?   # pipefail: jq나 curl이 실패하면 0이 아니다 (응답 도중 끊겨도 code는 200일 수 있다)
  if [ "$rc" -ne 0 ] || [[ $code != 2?? ]]; then
    echo "디스코드 전송 실패 (HTTP ${code:-000}, 종료 코드 $rc): $title" >&2
    return 1
  fi
  [ "$MODE" = run ] || echo "디스코드로 보냄: $title"
}

# 쿨다운 알림: 같은 key는 COOLDOWN 동안 한 번만
alert() {
  local key=$1 f="$STATE_DIR/cooldown/$1" last
  last=$(cat "$f" 2>/dev/null || echo 0)
  [ $(( NOW - last )) -lt "$COOLDOWN" ] && return 0
  send "$2" "$3" "$4" || return 1   # 못 보냈으면 쿨다운을 남기지 않아 다음 실행(1분 뒤)에 다시 시도한다
  [ "$MODE" = dry ] || echo "$NOW" >"$f"
}

# 상태형 알림: 처음엔 바로, 계속되면 쿨다운마다, 풀리면 resolve가 복구 알림
fire() {
  local key=$1 level=$2 title=$3 desc=$4 f="$STATE_DIR/firing/$1" since
  # 첫 알림을 실제로 보낸 적이 있을 때만(쿨다운 기록이 있을 때만) "계속"으로 보낸다
  if [ -f "$f" ] && [ -f "$STATE_DIR/cooldown/$key" ]; then
    since=$(head -n1 "$f")
    alert "$key" "$level" "$title (계속 · $(( (NOW - since) / 60 ))분째)" "$desc"
  else
    [ "$MODE" = dry ] || [ -f "$f" ] || { printf '%s\n%s\n' "$NOW" "$title" >"$f"; rm -f "$STATE_DIR/cooldown/$key"; }
    alert "$key" "$level" "$title" "$desc"
  fi
}
resolve() {
  local f="$STATE_DIR/firing/$1" since title
  [ -f "$f" ] || return 0
  since=$(sed -n 1p "$f"); title=$(sed -n 2p "$f")
  [ "$MODE" = dry ] || rm -f "$f" "$STATE_DIR/cooldown/$1"
  send ok "✅ 복구: $title" "$(( (NOW - since) / 60 ))분 만에 정상으로 돌아왔어요."
}

# 연속 실패 횟수 (배포 중 잠깐 멈춘 것과 진짜 장애를 구분)
streak() { local f="$STATE_DIR/streak/$1" n; n=$(( $(cat "$f" 2>/dev/null || echo 0) + 1 )); echo "$n" >"$f"; echo "$n"; }
streak_reset() { rm -f "$STATE_DIR/streak/$1"; }

# 연속 n회 실패하면 fire, 성공하면 resolve
check() {  # check <key> <필요 연속 횟수> <ok:0|1> <level> <제목> <내용>
  local key=$1 need=$2 ok=$3
  if [ "$ok" = 1 ]; then streak_reset "$key"; resolve "$key"; return; fi
  [ "$(streak "$key")" -ge "$need" ] && fire "$key" "$4" "$5" "$6"
}

# ── 1. 컨테이너 (app·db·caddy가 떠 있는지, 재시작·OOM) ──────────────
check_containers() {
  if ! docker info >/dev/null 2>&1; then
    check docker 2 0 crit "Docker 응답 없음" "컨테이너 상태를 읽을 수 없어요. \`sudo systemctl status docker\`"
    return
  fi
  check docker 2 1
  local svc id st health restarts oom prev
  for svc in app db caddy; do
    id=$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
                       --filter "label=com.docker.compose.service=$svc" | head -n1)
    if [ -z "$id" ]; then
      check "ctr_$svc" 2 0 crit "컨테이너 없음: $svc" "\`docker compose ps\`로 확인하세요."
      continue
    fi
    read -r st health restarts oom < <(docker inspect -f \
      '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}} {{.RestartCount}} {{.State.OOMKilled}}' "$id")
    if [ "$st" != running ] || [ "$health" = unhealthy ]; then
      check "ctr_$svc" 2 0 crit "컨테이너 멈춤: $svc" "상태: \`$st\` / 헬스: \`$health\`"$'\n'"\`docker compose logs --tail 100 $svc\`"
    else
      check "ctr_$svc" 2 1
    fi
    # 재시작 횟수가 늘었으면 (OOM·크래시로 자동 재시작)
    prev=$(cat "$STATE_DIR/restarts_${svc}_$id" 2>/dev/null || echo "$restarts")
    if [ "$restarts" -gt "$prev" ]; then
      local why="크래시 후 자동 재시작됐어요."
      [ "$oom" = true ] && why="**메모리 부족(OOM)으로 죽었다가** 자동 재시작됐어요."
      # 못 보냈으면 이전 횟수를 그대로 둬서 다음 실행에 다시 알린다
      alert "restart_$svc" warn "컨테이너 재시작: $svc" "$why (누적 ${restarts}회)" || continue
    fi
    rm -f "$STATE_DIR"/restarts_"${svc}"_*
    echo "$restarts" >"$STATE_DIR/restarts_${svc}_$id"
  done
}

# ── 2. 앱 헬스 + 바깥에서 접속되는지 + 인증서 ───────────────────────
check_endpoints() {
  if curl -fsS -m 5 http://127.0.0.1:8081/actuator/health 2>/dev/null | grep -q '"UP"'; then
    check app_health 2 1
  else
    check app_health 2 0 crit "앱 헬스체크 실패" "\`/actuator/health\`가 UP이 아니에요 (2분 연속). DB 연결이나 앱 기동을 확인하세요."
  fi

  [ -n "$DOMAIN" ] || return 0
  local code
  code=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/v1/auth/config" 2>/dev/null || echo 000)
  if [ "$code" = 200 ]; then
    check public 2 1
  else
    check public 2 0 crit "사이트 접속 불가" "\`https://$DOMAIN\` 응답 코드: \`$code\` (2분 연속). Caddy·인증서·DNS를 확인하세요."
  fi

  # 인증서 만료: 한 시간에 한 번만 본다. Caddy가 30일 전에 갱신하므로 14일 미만이면 갱신이 실패하고 있는 것.
  local last end days
  last=$(cat "$STATE_DIR/cert_checked" 2>/dev/null || echo 0)
  if [ $(( NOW - last )) -ge 3600 ]; then
    [ "$MODE" = dry ] || echo "$NOW" >"$STATE_DIR/cert_checked"
    end=$(echo | timeout 10 openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" 2>/dev/null \
          | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
    if [ -n "$end" ]; then
      days=$(( ( $(date -d "$end" +%s) - NOW ) / 86400 ))
      if [ "$days" -lt 14 ]; then fire cert warn "HTTPS 인증서 곧 만료" "${days}일 남았어요. \`docker compose logs caddy\`에서 갱신 오류를 확인하세요."
      else resolve cert; fi
    fi
  fi
}

# ── 3. 트래픽 (Caddy 접근 로그 최근 1분): DDoS·폭주 IP·5xx·인증 실패·스캔·느려짐 ──
check_traffic() {
  [ -f "$ACCESS_LOG" ] || return 0
  local tmp; tmp=$(mktemp)
  # 필드: IP, 상태코드, 메서드, 경로(쿼리 제외), 처리시간(초)
  tail -n 200000 "$ACCESS_LOG" | jq -rR --argjson since $(( NOW - 60 )) '
    fromjson? | select(.ts? and .ts >= $since and .request?)
    | [(.request.client_ip // .request.remote_ip // "-"), (.status // 0 | tostring),
       (.request.method // "-"), ((.request.uri // "-") | split("?")[0] | .[0:120]),
       (.duration // 0 | tostring)] | @tsv' >"$tmp" 2>/dev/null

  local total; total=$(wc -l <"$tmp" | tr -d ' ')
  echo "$total" >"$STATE_DIR/last_rpm"
  if [ "$total" -eq 0 ]; then rm -f "$tmp"; return 0; fi

  top_ips()   { cut -f1 | sort | uniq -c | sort -rn | head -n "${1:-3}" | awk '{printf "`%s` %s회\n", $2, $1}'; }
  top_paths() { awk -F'\t' '{print $3" "$4}' | sort | uniq -c | sort -rn | head -n "${1:-3}" | awk '{c=$1; $1=""; printf "`%s` %s회\n", substr($0,2), c}'; }

  # 전체 폭증
  if [ "$total" -gt "$TOTAL_RPM" ]; then
    alert traffic crit "트래픽 폭증 (DDoS 의심)" "최근 1분 요청 **${total}건** (기준 ${TOTAL_RPM})"$'\n\n'"**많이 보낸 IP**"$'\n'"$(top_ips 5 <"$tmp")"$'\n\n'"**많이 요청된 경로**"$'\n'"$(top_paths 3 <"$tmp")"
  fi

  # 한 IP 폭주
  local top_line top_n top_ip
  top_line=$(cut -f1 "$tmp" | sort | uniq -c | sort -rn | head -n1)
  top_n=$(awk '{print $1}' <<<"$top_line"); top_ip=$(awk '{print $2}' <<<"$top_line")
  if [ "${top_n:-0}" -gt "$IP_RPM" ]; then
    alert ip_flood crit "한 IP에서 요청 폭주" "\`$top_ip\` 에서 최근 1분 **${top_n}건** (기준 ${IP_RPM})"$'\n\n'"**이 IP가 부른 경로**"$'\n'"$(awk -F'\t' -v ip="$top_ip" '$1==ip' "$tmp" | top_paths 3)"$'\n\n'"_부스 와이파이처럼 여러 명이 같은 IP를 쓰면 정상일 수도 있어요._"
  fi

  # 5xx
  local n5; n5=$(awk -F'\t' '$2>=500' "$tmp" | wc -l | tr -d ' ')
  if [ "$n5" -gt "$ERR5XX_PM" ]; then
    alert err5xx crit "서버 오류(5xx) 급증" "최근 1분 **${n5}건** / 전체 ${total}건"$'\n\n'"**오류 난 경로**"$'\n'"$(awk -F'\t' '$2>=500' "$tmp" | top_paths 5)"
  fi

  # 인증 실패 (무차별 대입·탈취 토큰 시도)
  local na; na=$(awk -F'\t' '$2==401 || $2==403' "$tmp" | wc -l | tr -d ' ')
  if [ "$na" -gt "$AUTHFAIL_PM" ]; then
    alert authfail warn "인증 실패 급증 (무차별 대입·토큰 도용 의심)" "최근 1분 401/403 **${na}건**"$'\n\n'"**IP**"$'\n'"$(awk -F'\t' '$2==401 || $2==403' "$tmp" | top_ips 5)"$'\n\n'"**경로**"$'\n'"$(awk -F'\t' '$2==401 || $2==403' "$tmp" | top_paths 3)"
  fi

  # 취약점 스캔 (/.env, /.git, wp-login, phpMyAdmin, actuator 등)
  local probe_re='/\.(env|git|aws|ssh|DS_Store)|wp-(login|admin|content)|xmlrpc|phpmyadmin|\.php$|cgi-bin|/actuator|/server-status|/\.well-known/security|/config\.json|/backup'
  local np; np=$(re="$probe_re" awk -F'\t' 'tolower($4) ~ ENVIRON["re"]' "$tmp" | wc -l | tr -d ' ')
  if [ "$np" -gt "$PROBE_PM" ]; then
    alert probe warn "취약점 스캔 감지" "최근 1분 의심 경로 요청 **${np}건** (막혀 있으면 대부분 404)"$'\n\n'"**IP**"$'\n'"$(re="$probe_re" awk -F'\t' 'tolower($4) ~ ENVIRON["re"]' "$tmp" | top_ips 3)"$'\n\n'"**경로**"$'\n'"$(re="$probe_re" awk -F'\t' 'tolower($4) ~ ENVIRON["re"]' "$tmp" | top_paths 5)"
  fi

  # 느려짐 (요청이 20건 이상일 때만 p95 계산)
  if [ "$total" -ge 20 ]; then
    local p95; p95=$(cut -f5 "$tmp" | sort -g | awk '{a[NR]=$1} END {i=int(NR*0.95); if (i<1) i=1; printf "%.2f", a[i]}')
    if awk -v p="$p95" -v t="$SLOW_P95" 'BEGIN {exit !(p > t)}'; then
      check slow 2 0 warn "응답 느려짐" "최근 1분 p95 **${p95}초** (기준 ${SLOW_P95}초, 요청 ${total}건)"
    else
      check slow 2 1
    fi
  fi
  rm -f "$tmp"
}

# ── 4. SSH (보안 그룹이 관리자 IP만 열어 두므로 실패가 보이면 이상 신호) ──
check_ssh() {
  command -v journalctl >/dev/null || return 0
  local cur="$STATE_DIR/ssh.cursor" args=(-t sshd -t sshd-session -o cat --no-pager --cursor-file="$cur")
  [ -f "$cur" ] || args+=(--since "-2min")
  [ "$MODE" = dry ] && args=(-t sshd -t sshd-session -o cat --no-pager --since "-1min")
  local lines; lines=$(journalctl "${args[@]}" 2>/dev/null)
  [ -n "$lines" ] || return 0

  local fails; fails=$(grep -E 'Failed (password|publickey)|Invalid user|authentication failure|maximum authentication attempts' <<<"$lines")
  local nf; nf=$(grep -c . <<<"$fails")
  if [ -n "$fails" ] && [ "$nf" -ge "$SSH_FAIL" ]; then
    alert ssh_fail crit "SSH 무단 접속 시도" "실패 **${nf}건**. 보안 그룹 22번이 내 IP만 열려 있는지 확인하세요."$'\n\n'"$(grep -oE 'from [0-9a-fA-F.:]+' <<<"$fails" | sort | uniq -c | sort -rn | head -n 5 | awk '{printf "`%s` %s회\n", $3, $1}')"
  fi
  # 로그인 성공은 모두 알린다 (CD 배포도 여기 찍힌다)
  grep -E '^Accepted ' <<<"$lines" | while read -r _ method _ user _ ip _; do
    send info "SSH 로그인" "\`$user\` / \`$ip\` ($method)"$'\n'"_GitHub Actions 배포 중이면 정상이에요._"
  done
}

# ── 5. 시스템 자원 (CPU·메모리·디스크) ─────────────────────────────
check_system() {
  # CPU: 지난 실행과의 /proc/stat 차이로 계산. 3분 연속 넘으면 알림
  if [ -r /proc/stat ]; then
    local u n s i w q sq st total idle prev pct
    read -r _ u n s i w q sq st _ </proc/stat
    total=$(( u + n + s + i + w + q + sq + st )); idle=$(( i + w ))
    prev=$(cat "$STATE_DIR/cpu" 2>/dev/null || echo "")
    [ "$MODE" = dry ] || echo "$total $idle" >"$STATE_DIR/cpu"
    if [ -n "$prev" ]; then
      set -- $prev
      if [ $(( total - $1 )) -gt 0 ]; then
        pct=$(( 100 * ( (total - $1) - (idle - $2) ) / (total - $1) ))
        echo "$pct" >"$STATE_DIR/last_cpu"
        if [ "$pct" -ge "$CPU_PCT" ]; then
          check cpu 3 0 warn "CPU 과부하" "CPU **${pct}%** (3분 연속 ${CPU_PCT}% 이상). t3 인스턴스는 CPU 크레딧을 다 쓰면 느려지거나 추가 요금이 나와요."
        else check cpu 3 1; fi
      fi
    fi
  fi

  # 메모리: MemAvailable 기준. 2분 연속
  if [ -r /proc/meminfo ]; then
    local mt ma mp
    mt=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo); ma=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
    mp=$(( 100 - 100 * ma / mt ))
    echo "$mp" >"$STATE_DIR/last_mem"
    if [ "$mp" -ge "$MEM_PCT" ]; then
      check mem 2 0 warn "메모리 부족" "사용률 **${mp}%** (남은 메모리 $(( ma / 1024 ))MB). 계속되면 앱이 OOM으로 죽을 수 있어요."
    else check mem 2 1; fi
  fi

  # 디스크
  local dp; dp=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
  echo "$dp" >"$STATE_DIR/last_disk"
  if [ "${dp:-0}" -ge "$DISK_PCT" ]; then
    fire disk warn "디스크 부족" "\`/\` 사용률 **${dp}%**. \`docker system prune\`이나 로그 정리가 필요해요."
  else resolve disk; fi
}

# ── 6. 앱 ERROR 로그 ──────────────────────────────────────────────
check_app_errors() {
  local id; id=$(docker ps -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" --filter "label=com.docker.compose.service=app" 2>/dev/null | head -n1)
  [ -n "$id" ] || return 0
  local errs; errs=$(docker logs --since 60s "$id" 2>&1 | grep -E '^[0-9T:.+-]+ ERROR ')
  local ne; ne=$(grep -c . <<<"$errs")
  if [ -n "$errs" ] && [ "$ne" -gt "$APPERR_PM" ]; then
    alert app_error warn "앱 ERROR 로그 급증" "최근 1분 **${ne}건**. 첫 줄:"$'\n'"\`\`\`$(head -n1 <<<"$errs" | cut -c1-300)\`\`\`"
  fi
}

case $MODE in
  test)
    send info "테스트 알림" "CloudCost 서버 감시가 디스코드에 연결됐어요. 문제가 생길 때만 알림이 와요." || exit 1
    ;;
  hello)
    send info "서버 감시 시작" "1분마다 검사하고, 이상이 있을 때만 알려요."$'\n'"컨테이너 · 앱 헬스 · 외부 접속 · 인증서 · 트래픽 폭증/DDoS · 5xx · 인증 실패 · 취약점 스캔 · 응답 속도 · SSH · CPU · 메모리 · 디스크 · 앱 ERROR" || exit 1
    ;;
  run|dry)
    check_containers
    check_endpoints
    check_traffic
    check_ssh
    check_system
    check_app_errors
    if [ "$MODE" = dry ]; then
      printf '\n--- 현재 값: 분당 요청 %s · CPU %s%% · 메모리 %s%% · 디스크 %s%%\n' \
        "$(cat "$STATE_DIR/last_rpm" 2>/dev/null || echo -)" "$(cat "$STATE_DIR/last_cpu" 2>/dev/null || echo -)" \
        "$(cat "$STATE_DIR/last_mem" 2>/dev/null || echo -)" "$(cat "$STATE_DIR/last_disk" 2>/dev/null || echo -)"
    fi
    ;;
  *) echo "사용: $0 [run|test|dry|hello]" >&2; exit 2 ;;
esac
exit 0
