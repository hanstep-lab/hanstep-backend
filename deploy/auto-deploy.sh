#!/usr/bin/env bash
# 신한걸음 자동 배포
# GitHub Actions가 배포 전용 SSH 키로 접속하면 forced command로 이 스크립트만 실행된다.
set -euo pipefail

main() {
  local BASE_DIR="${HOME}/hanstep"
  local BE_DIR="${BASE_DIR}/hanstep-backend"
  local AI_DIR="${BASE_DIR}/hanstep-AI"
  local LOCK_FILE="${BASE_DIR}/.auto-deploy.lock"
  local OFF_FLAG="${BASE_DIR}/AUTO_DEPLOY_OFF"
  local LOG_DIR="${BASE_DIR}/deploy-logs"
  local HEALTH_URL="https://hanstep-shinhan.duckdns.org/api/v1/health"
  local HEALTH_TIMEOUT_SEC=120

  # 로그: 화면과 파일에 함께 남긴다
  mkdir -p "$LOG_DIR"
  local LOG_FILE="${LOG_DIR}/$(date +%Y%m%d-%H%M%S).log"
  exec > >(tee -a "$LOG_FILE") 2>&1

  # 첫 줄: 호출한 레포·커밋 (기록만 하고 실행하지 않는다)
  echo "trigger: ${SSH_ORIGINAL_COMMAND:-manual}"
  echo "start: $(date '+%F %T')"

  trap 'echo "[FAIL] line ${LINENO}: 배포 중단"; exit 1' ERR

  # 1. 잠금: 동시에 호출되면 앞 배포가 끝날 때까지 기다린다(최대 15분)
  exec 9>"$LOCK_FILE"
  if ! flock -w 900 9; then
    echo "[FAIL] lock timeout"
    exit 1
  fi

  # 2. 발표·리허설용 스위치
  if [[ -f "$OFF_FLAG" ]]; then
    echo "skipped: ${OFF_FLAG} exists"
    exit 0
  fi

  # 3. 두 레포 모두 develop인지 확인하고 최신으로
  echo "== git pull =="
  local dir branch
  for dir in "$BE_DIR" "$AI_DIR"; do
    branch="$(git -C "$dir" branch --show-current)"
    if [[ "$branch" != "develop" ]]; then
      echo "[FAIL] ${dir} is on '${branch}', not develop"
      exit 1
    fi
    git -C "$dir" pull --ff-only origin develop
    echo "$(basename "$dir"): $(git -C "$dir" log -1 --format='%h %s')"
  done

  cd "$BE_DIR"
  local -a DC=(docker compose -f deploy/compose.prod.yml)

  # 4. 마이그레이션: 이름순 실행 (파일은 여러 번 실행해도 결과가 같게 작성)
  echo "== migrations =="
  local f
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    echo "apply: $(basename "$f")"
    "${DC[@]}" exec -T mysql sh -c \
      'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$f"
  done < <(find deploy/migrations -maxdepth 1 -name '*.sql' -type f | LC_ALL=C sort)

  # 5. 문법 검사 → 빌드 → 교체 (빌드 실패 시 기존 컨테이너는 그대로)
  echo "== compose =="
  "${DC[@]}" config --quiet
  "${DC[@]}" build
  "${DC[@]}" up -d

  # 7. health 확인 (최대 2분)
  echo "== health =="
  local waited=0
  until curl -fsS --max-time 5 "$HEALTH_URL" | grep -q '"status":"UP"'; do
    if (( waited >= HEALTH_TIMEOUT_SEC )); then
      echo "[FAIL] health not UP within ${HEALTH_TIMEOUT_SEC}s"
      exit 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "OK: health UP (${waited}s), end: $(date '+%F %T')"
}

main "$@"