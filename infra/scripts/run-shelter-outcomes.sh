#!/bin/sh
# HDFS 백필(서버 2)을 WebHDFS 로 훑어 최근 3년 보호소 결과 통계를 dashboard_stat 에 upsert 한다.
# shelter-outcomes.service 가 호출한다. 기준일은 KST 오늘 — 창의 끝(오늘-60일)이 여기서 정해진다.
set -eu

day=$(TZ=Asia/Seoul date +%F)

cd /home/ubuntu/shelter-loader
exec ./.venv/bin/python shelter_outcomes.py --today "$day"
