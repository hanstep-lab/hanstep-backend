#!/bin/sh
# 로컬 개발 전용 시드(db/dev/*.sql)를 01~03 뒤, 05 앞에 실행한다(#119).
# 로컬 docker-compose.yml 만 db/dev 를 /docker-entrypoint-dev.d 로 마운트한다. 운영 compose 는 마운트하지 않아
# 아무것도 하지 않는다(운영 환율은 실데이터만 둔다. Mock 환율 04 시드는 db/dev 에 있다).
# db/init 마운트 안에 파일을 겹쳐 마운트하면 읽기 전용이라 컨테이너가 뜨지 않아서 이 방식으로 한다.
# mysql 이미지 entrypoint 는 실행 권한이 있으면 따로 실행하고(Windows Docker Desktop 마운트) 없으면 source 한다(Linux).
# 둘 다 되도록 entrypoint 함수 대신 mysql 클라이언트를 직접 쓴다(초기화 중 임시 서버는 소켓으로만 받는다).
set -e
if [ -d /docker-entrypoint-dev.d ]; then
  for f in /docker-entrypoint-dev.d/*.sql; do
    [ -e "$f" ] || continue
    echo "[dev seeds] running $f"
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --default-character-set=utf8mb4 \
      "$MYSQL_DATABASE" < "$f"
  done
fi
