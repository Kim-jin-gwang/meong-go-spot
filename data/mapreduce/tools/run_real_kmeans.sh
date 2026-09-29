#!/usr/bin/env bash
# 실벡터 K-Means 색인 — InitCenters(k-means++, 저수지 표본) → KMeansDriver 반복 → assignments. 시간·로그를 남긴다.
# 서버 2에서: nohup ./run_real_kmeans.sh 256 10 1e-3 100000 42 > logs/nohup-k256.out 2>&1 &
# 사용법: ./run_real_kmeans.sh <k> [maxIter=10] [eps=1e-3] [sample=100000] [seed=42]
# 경로는 /embeddings/ACTIVE(계약 §4 ④)에서 만든다 — 모델이 바뀌면 이 스크립트는 그대로, 포인터만 바뀐다.
# 산출: $BASE/index/kmeans-k<K>/{centers-init.tsv, work/centers-N/, work/assignments/, centers.tsv(최종 중심 고정 이름)}
set -euo pipefail
K="$1"
MAX_ITER="${2:-10}"
EPS="${3:-1e-3}"
SAMPLE="${4:-100000}"
SEED="${5:-42}"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export HADOOP_HOME=/opt/hadoop
export PATH="$PATH:/opt/hadoop/bin"

ACTIVE=$(hdfs dfs -cat /embeddings/ACTIVE | tr -d '[:space:]')
BASE="/embeddings/$ACTIVE"
META="$BASE/_meta.json"
VECTORS="$BASE/shelter-backfill/yyyymm=*/vectors-*.tsv"   # 글롭은 하둡이 푼다 — detections jsonl 은 제외됨
INDEX="$BASE/index/kmeans-k$K"
JAR="${JAR:-$HOME/kmeans-test/mungo-mapreduce.jar}"

mkdir -p logs
LOG="logs/kmeans-k$K.log"
hdfs dfs -rm -r -f "$INDEX/work" >/dev/null 2>&1 || true
hdfs dfs -mkdir -p "$INDEX"

START=$(date +%s)
echo "== $(date -Is) InitCenters k=$K sample=$SAMPLE seed=$SEED · $VECTORS" | tee "$LOG"
hadoop jar "$JAR" com.mungo.mapreduce.kmeans.InitCenters \
  -Dmungo.vector.meta="$META" -Dkmeans.init.sample="$SAMPLE" \
  "$VECTORS" "$K" "$INDEX/centers-init.tsv" "$SEED" 2>&1 | tee -a "$LOG"
INIT_END=$(date +%s)

echo "== $(date -Is) KMeansDriver maxIter=$MAX_ITER eps=$EPS (init $((INIT_END - START))s)" | tee -a "$LOG"
hadoop jar "$JAR" com.mungo.mapreduce.kmeans.KMeansDriver \
  -Dmungo.vector.meta="$META" \
  "$VECTORS" "$INDEX/centers-init.tsv" "$INDEX/work" "$MAX_ITER" "$EPS" 2>&1 | tee -a "$LOG"
END=$(date +%s)

# 최종 중심을 고정 이름으로 — 소비자(kNN 조인·상주 서비스)는 centers.tsv 만 본다
LAST=$(hdfs dfs -ls "$INDEX/work" | grep -o 'centers-[0-9]*' | sort -t- -k2 -n | tail -1)
hdfs dfs -cat "$INDEX/work/$LAST/part-*" | hdfs dfs -put -f - "$INDEX/centers.tsv"
# 배정도 고정 위치로 — 일일 증분(index_tools.py assign)이 같은 디렉터리에 파일을 덧붙인다.
# work/ 는 재현용 중간 산출물이라 소비자가 볼 곳이 아니다.
# 중심이 바뀌면 기존 일일 배정의 무리 번호는 다른 뜻이 되므로 **전부 지우고** base 만 새로 깐다.
# 빠진 파티션은 index_tools.py assign 이 다음 실행에서 스스로 찾아 채운다 (--partition 없이 실행).
hdfs dfs -rm -r -f "$INDEX/assignments" "$INDEX/_centers.sha256" >/dev/null 2>&1 || true
hdfs dfs -mkdir -p "$INDEX/assignments"
hdfs dfs -cat "$INDEX/work/assignments/part-*" | hdfs dfs -put -f - "$INDEX/assignments/base.tsv"
echo "== $(date -Is) 완료: init $((INIT_END - START))s · kmeans $((END - INIT_END))s · 총 $((END - START))s · 최종 중심 $LAST → $INDEX/centers.tsv · 배정 $INDEX/work/assignments" | tee -a "$LOG"
