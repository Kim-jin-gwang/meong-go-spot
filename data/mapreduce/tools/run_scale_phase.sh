#!/usr/bin/env bash
# 스케일 실험 1단계 실행 — K-Means 2반복을 돌리며 로그·시간·노드 배치를 기록한다.
# 사용법: ./run_scale_phase.sh <라벨> <combiner: true|false>
# 산출: logs/<라벨>.log (드라이버 stdout+카운터), logs/<라벨>-nodes.log (노드별 컨테이너 폴링),
#       logs/<라벨>-wall.txt (전체 초)
set -euo pipefail
LABEL="$1"
COMBINER="${2:-true}"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export HADOOP_HOME=/opt/hadoop
export PATH="$PATH:/opt/hadoop/bin:/opt/hadoop/sbin"

mkdir -p logs
hdfs dfs -rm -r -f "/scale/work-$LABEL" >/dev/null 2>&1 || true

# 노드별 실행 컨테이너 폴링 (10초 간격) — "누가 얼마나 나눠 들었나"의 증거
(
  while true; do
    echo "--- $(date +%s)"
    yarn node -list 2>/dev/null | grep -E 'RUNNING' || true
    sleep 10
  done
) > "logs/$LABEL-nodes.log" &
POLL_PID=$!
trap 'kill $POLL_PID 2>/dev/null || true' EXIT

START=$(date +%s)
hadoop jar mungo-mapreduce.jar com.mungo.mapreduce.kmeans.KMeansDriver \
  -Dkmeans.combiner="$COMBINER" \
  /scale/vectors.tsv /scale/centers.tsv "/scale/work-$LABEL" 2 1e-9 \
  > "logs/$LABEL.log" 2>&1
END=$(date +%s)
echo $((END - START)) > "logs/$LABEL-wall.txt"
echo "$LABEL 완료: $((END - START))초 (combiner=$COMBINER)"
