#!/usr/bin/env bash
# ai-server 컨테이너 StartedAt → /health/ready 가 true 가 될 때까지 걸린 시간(초)을 5초 간격으로 잰다(#133).
# 사용(서버, ~/hanstep/hanstep-backend 에서):
#   bash measure-ai-ready.sh wait  <라벨>   # 자동 배포를 기다렸다가 새 컨테이너를 잰다(캐시 없음)
#   bash measure-ai-ready.sh recreate <라벨> # ai-server 만 --force-recreate 하고 잰다(캐시 있음)
# 결과는 ~/hanstep/measure-ai-ready.log 에 한 줄씩 쌓인다. .env 값은 출력하지 않는다.
set -u
MODE="${1:?wait 또는 recreate}"
LABEL="${2:-run}"
INTERVAL=5
TIMEOUT=1800
LOG="$HOME/hanstep/measure-ai-ready.log"
DC="docker compose -f deploy/compose.prod.yml"
CACHE=/app/artifacts/rag/embedding-cache

current_id() { $DC ps -q ai-server 2>/dev/null; }

READY_PY='import json,urllib.request as u,urllib.error as e
try:
    b=u.urlopen("http://127.0.0.1:8000/health/ready",timeout=4).read()
except e.HTTPError as x:
    b=x.read()
except Exception as x:
    print("down\t"+type(x).__name__); raise SystemExit
d=json.loads(b); print(("true" if d.get("ready") else "false")+"\t"+json.dumps(d.get("checks"),separators=(",",":")))'

OLD_ID="$(current_id)"
echo "기존 컨테이너 ID=${OLD_ID:-(없음)}"

case "$MODE" in
  wait)
    echo "ID 가 바뀐 새 컨테이너가 뜰 때까지 기다린다(Ctrl+C 로 중단)."
    ;;
  recreate)
    $DC up -d --no-deps --force-recreate ai-server || { echo "재생성 실패"; exit 1; }
    ;;
  *) echo "MODE 는 wait 또는 recreate"; exit 1 ;;
esac

# 재생성 중에는 ps -q 가 빈 값일 수 있다. 빈 값·기존 ID·아직 running 이 아닌 컨테이너는 건너뛴다
while :; do
  NEW_ID="$(current_id)"
  if [ -n "$NEW_ID" ] && [ "$NEW_ID" != "$OLD_ID" ] \
     && [ "$(docker inspect -f '{{.State.Running}}' "$NEW_ID" 2>/dev/null)" = "true" ]; then
    break
  fi
  if [ "$MODE" = recreate ]; then echo "재생성했는데 새 ID 가 아니다(ID=$NEW_ID)"; exit 1; fi
  sleep "$INTERVAL"
done

START="$(docker inspect -f '{{.State.StartedAt}}' "$NEW_ID")"
START_S="$(date -d "$START" +%s)"
echo "새 컨테이너 ID=$NEW_ID StartedAt=$START"

FIRST=1
while :; do
  NOW_S="$(date +%s)"
  ELAPSED=$((NOW_S - START_S))
  OUT="$(docker exec "$NEW_ID" python -c "$READY_PY" 2>/dev/null || echo "down	exec")"
  STATE="${OUT%%$'\t'*}"
  echo "+${ELAPSED}s $OUT"
  if [ "$STATE" = "true" ]; then
    if [ "$FIRST" = 1 ]; then
      echo "주의: 첫 확인부터 ready=true 다. 기동 직후에 시작하지 않았으면 이 값은 상한일 뿐이다."
    fi
    break
  fi
  FIRST=0
  if [ "$ELAPSED" -ge "$TIMEOUT" ]; then echo "제한 시간 ${TIMEOUT}s 초과"; break; fi
  sleep "$INTERVAL"
done

OWNER="$(docker exec "$NEW_ID" stat -c '%u:%g' "$CACHE" 2>/dev/null)"
SIZE="$(docker exec "$NEW_ID" du -sh "$CACHE" 2>/dev/null | cut -f1)"
LINE="$(date -u +%FT%TZ) label=$LABEL mode=$MODE old_id=${OLD_ID:0:12} new_id=${NEW_ID:0:12} started=$START ready_s=$ELAPSED state=$STATE owner=$OWNER size=$SIZE"
echo "$LINE" | tee -a "$LOG"
