#!/bin/sh
# 그날 분실동물 스냅샷(HDFS /data/lost/raw/dt=KST 오늘)을 서비스 PostgreSQL 에 적재한다.
# lost-loader.service 가 호출한다. 날짜는 KST 로 — 23:20 실행이 자정을 넘겨도 같은 파티션을 읽어야 한다.
set -eu

day=$(TZ=Asia/Seoul date +%F)

cd /home/ubuntu/shelter-loader
exec ./.venv/bin/python lost_ingestion.py --date "$day"
