#!/usr/bin/env bash
# 공공데이터 적재기(data/collector/postgres_loader.py) 전용 PostgreSQL 계정을 만든다.
#
# 왜 전용 계정인가 — 적재기는 공공 동물 데이터만 쓴다. 애플리케이션 계정을 재사용하면
# member·auth_session·chat_*·match_* 까지 권한이 열리고, DSN 이 유출되면 회원 정보까지
# 노출된다. 적재기가 실제로 건드리는 6개 테이블에만 권한을 준다.
#
# 비밀번호는 이 스크립트가 서버에서 만들고 출력하지 않는다. 앱 계정의 비밀번호를 알 필요도
# 없다 — postgres 컨테이너 안에서 로컬 소켓으로 접속하기 때문이다.
#
# 멱등: 다시 돌리면 비밀번호를 새로 만들어 갱신한다. 그때는 env 파일도 같이 갱신되므로
# 적재기를 재기동해야 한다.
#
# 사용법 (서버 1):
#   sudo bash setup-loader-db.sh
set -euo pipefail

CONTAINER=meong-go-spot-postgres-1
ROLE=shelter_loader
ENV_DIR=/home/ubuntu/.secrets/loader
ENV_FILE="$ENV_DIR/shelter-loader.env"

# 적재기가 실제로 쓰는 테이블과 동작 (public_ingestion.py·postgres_loader.py·lost_ingestion.py 의 SQL 기준).
# RETURNING 과 ON CONFLICT DO UPDATE 때문에 SELECT·UPDATE 가 함께 필요하다.
read -r -d '' GRANTS <<'SQL' || true
GRANT SELECT, INSERT, UPDATE         ON animal_case          TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON animal_case_location TO shelter_loader;
GRANT SELECT, INSERT, UPDATE, DELETE ON animal_photo         TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON ingestion_run        TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON shelter              TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON shelter_animal       TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON lost_report          TO shelter_loader;
GRANT SELECT, INSERT, UPDATE         ON dashboard_stat       TO shelter_loader;
SQL

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
    echo "$CONTAINER 이 돌고 있지 않습니다." >&2
    exit 1
fi

PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"

docker exec -i -e LOADER_PASSWORD="$PASSWORD" "$CONTAINER" sh -s <<'INNER'
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shelter_loader') THEN
        CREATE ROLE shelter_loader LOGIN;
    END IF;
END
\$\$;
ALTER ROLE shelter_loader WITH PASSWORD '$LOADER_PASSWORD';
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO shelter_loader;
GRANT USAGE ON SCHEMA public TO shelter_loader;
SQL
INNER

docker exec -i "$CONTAINER" sh -s <<INNER
set -eu
psql -v ON_ERROR_STOP=1 -U "\$POSTGRES_USER" -d "\$POSTGRES_DB" >/dev/null <<SQL
$GRANTS
SQL
INNER

# DSN 은 루프백으로 붙는다 — compose.prod.yml 이 5432 를 127.0.0.1 에만 publish 한다.
DB_NAME="$(docker exec "$CONTAINER" sh -c 'printf %s "$POSTGRES_DB"')"
install -d -o ubuntu -g ubuntu -m 700 "$ENV_DIR"
umask 077
cat > "$ENV_FILE" <<ENV
# setup-loader-db.sh 가 생성했다. 손으로 고치지 않는다.
SHELTER_POSTGRES_DSN=postgresql://$ROLE:$PASSWORD@127.0.0.1:5432/$DB_NAME
ENV
chown ubuntu:ubuntu "$ENV_FILE"
chmod 600 "$ENV_FILE"

echo "역할 $ROLE 생성·갱신 완료 (비밀번호는 출력하지 않습니다)"
echo "  DSN 파일: $ENV_FILE (ubuntu, 0600)"
echo "  권한: animal_case·animal_case_location·animal_photo·ingestion_run·shelter·shelter_animal·lost_report"
echo "  member·auth_session·chat_*·user_post·match_* 에는 권한이 없습니다"
echo
echo "비밀번호를 갱신했어도 재기동은 필요 없습니다 — shelter-loader.service 가 매 실행마다"
echo "EnvironmentFile 을 다시 읽습니다. 지금 바로 확인하려면: sudo systemctl start shelter-loader.service"
