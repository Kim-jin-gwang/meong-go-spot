#!/usr/bin/env bash
# 매칭 엔진(data/match_engine)과 매칭 worker(data/matching_worker) 전용 PostgreSQL 계정을 만든다.
#
# 두 계정을 나누는 이유 — 엔진은 후보를 **읽기만** 하고, worker 는 match_run 선점·match_candidate
# 적재를 **쓴다**. 엔진에 쓰기 권한이 있으면 사진 경로를 받는 HTTP 프로세스가 DB 를 고칠 수 있게 된다.
# 둘 다 member·auth_session·chat_*·user_post 에는 권한이 없다 (setup-loader-db.sh 와 같은 원칙).
#
# 비밀번호는 이 스크립트가 서버에서 만들고 출력하지 않는다. postgres 컨테이너 안에서 로컬 소켓으로
# 접속하므로 앱 계정 비밀번호도 필요 없다. 멱등 — 다시 돌리면 비밀번호를 새로 만들고 env 파일도 갱신하므로
# 두 서비스를 재기동한다.
#
# 사용법 (서버 1):
#   sudo bash setup-match-db.sh
set -euo pipefail

CONTAINER=meong-go-spot-postgres-1
ENV_DIR=/home/ubuntu/.secrets/match
ENGINE_ENV="$ENV_DIR/match-engine.env"
WORKER_ENV="$ENV_DIR/match-worker.env"
ENGINE_URL="http://127.0.0.1:8091/"

# 엔진: engine.py CANDIDATE_SQL 이 읽는 4개 테이블.
# worker: worker.py claim_next(match_run FOR UPDATE + animal_case·location·photo 읽기), complete(match_candidate
# INSERT, match_run UPDATE, animal_case 재검사), fail(match_run UPDATE).
read -r -d '' GRANTS <<'SQL' || true
GRANT SELECT                 ON animal_case, shelter_animal, animal_photo, animal_case_location TO match_engine;
GRANT SELECT                 ON animal_case, animal_photo, animal_case_location                 TO match_worker;
GRANT SELECT, UPDATE         ON match_run                                                       TO match_worker;
GRANT SELECT, INSERT         ON match_candidate                                                 TO match_worker;
SQL

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
    echo "$CONTAINER 이 돌고 있지 않습니다." >&2
    exit 1
fi

ENGINE_PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"
WORKER_PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"

docker exec -i -e ENGINE_PASSWORD="$ENGINE_PASSWORD" -e WORKER_PASSWORD="$WORKER_PASSWORD" "$CONTAINER" sh -s <<'INNER'
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'match_engine') THEN CREATE ROLE match_engine LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'match_worker') THEN CREATE ROLE match_worker LOGIN; END IF;
END
\$\$;
ALTER ROLE match_engine WITH PASSWORD '$ENGINE_PASSWORD';
ALTER ROLE match_worker WITH PASSWORD '$WORKER_PASSWORD';
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO match_engine, match_worker;
GRANT USAGE ON SCHEMA public TO match_engine, match_worker;
SQL
INNER

docker exec -i "$CONTAINER" sh -s <<INNER
set -eu
psql -v ON_ERROR_STOP=1 -U "\$POSTGRES_USER" -d "\$POSTGRES_DB" >/dev/null <<SQL
$GRANTS
SQL
INNER

DB_NAME="$(docker exec "$CONTAINER" sh -c 'printf %s "$POSTGRES_DB"')"
install -d -o ubuntu -g ubuntu -m 700 "$ENV_DIR"
umask 077
cat > "$ENGINE_ENV" <<ENV
# setup-match-db.sh 가 생성했다. 손으로 고치지 않는다.
MATCH_ENGINE_POSTGRES_DSN=postgresql://match_engine:$ENGINE_PASSWORD@127.0.0.1:5432/$DB_NAME
ENV
cat > "$WORKER_ENV" <<ENV
# setup-match-db.sh 가 생성했다. 손으로 고치지 않는다.
MATCH_WORKER_POSTGRES_DSN=postgresql://match_worker:$WORKER_PASSWORD@127.0.0.1:5432/$DB_NAME
MATCH_ENGINE_URL=$ENGINE_URL
ENV
chown ubuntu:ubuntu "$ENGINE_ENV" "$WORKER_ENV"
chmod 600 "$ENGINE_ENV" "$WORKER_ENV"

echo "역할 match_engine·match_worker 생성·갱신 완료 (비밀번호는 출력하지 않습니다)"
echo "  $ENGINE_ENV — 엔진: animal_case·shelter_animal·animal_photo·animal_case_location SELECT"
echo "  $WORKER_ENV — worker: match_run SELECT/UPDATE, match_candidate SELECT/INSERT, 케이스·사진·위치 SELECT"
echo "비밀번호를 갱신했으면: sudo systemctl restart match-engine.service matching-worker.service"
