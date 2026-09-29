#!/bin/sh
# 공공 보호동물 원문을 Kafka 에서 읽어 서비스 PostgreSQL 에 적재한다.
# shelter-loader.service 가 호출한다. systemd 는 ExecStart 에서 명령 치환을 하지 않으므로
# 수집 대상 날짜(KST)를 여기서 계산한다.
#
# 서버 1 에서 도는 이유: 서비스 PostgreSQL 은 127.0.0.1 에만 publish 되어 있어
# (compose.prod.yml) 서버 2 의 Airflow 에서는 닿지 않는다. docs/deploy-guide.md 참고.
set -eu

# 수집 DAG 는 22:30 KST 에 돌고 이 단위는 그 뒤에 실행된다. 자정을 넘겨 실행돼도 같은 날짜를
# 기록해야 하므로 UTC 가 아닌 KST 날짜를 쓴다.
day=$(TZ=Asia/Seoul date +%F)

cd /home/ubuntu/shelter-loader
exec ./.venv/bin/python postgres_loader.py     --bootstrap bd-master:9092     --run-type DAILY_INCREMENTAL     --requested-from "$day"     --requested-to "$day"
