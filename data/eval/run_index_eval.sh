#!/usr/bin/env bash
# K-Means 색인 평가 (서버 2 ~/eval-work) — HDFS 의 최종 중심·배정을 내려 후보 축소 recall 손실과 purity 를 낸다.
# 전제: ~/eval-work/{vectors,records,out/pairs.tsv} (2026-09-07 -108 평가 때 만든 로컬 사본), ~/eval-app 에 eval 스크립트.
# 사용법: cd ~/eval-work && nohup ~/eval-app/run_index_eval.sh 256 > out/index-eval-k256.out 2>&1 &
set -euo pipefail
K="${1:-256}"
SAMPLE="${2:-20000}"
SEED="${3:-42}"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$PATH:/opt/hadoop/bin"
PY="$HOME/ai/.venv/bin/python"
APP="$HOME/eval-app"

ACTIVE=$(hdfs dfs -cat /embeddings/ACTIVE | tr -d '[:space:]')
INDEX="/embeddings/$ACTIVE/index/kmeans-k$K"
LOCAL="index-k$K"
rm -rf "$LOCAL"
mkdir -p "$LOCAL" out
hdfs dfs -get "$INDEX/centers.tsv" "$LOCAL/centers.tsv"
hdfs dfs -get "$INDEX/work/assignments" "$LOCAL/assignments"
echo "== $(date -Is) 색인 내려받음: $(wc -l < "$LOCAL/centers.tsv") 중심 · 배정 $(cat "$LOCAL"/assignments/part-* | wc -l) 줄"

# 같은 --seed·--sample 이라 overall(정확 탐색) 과 nprobe 결과가 같은 쌍으로 비교된다
"$PY" "$APP/eval_recall.py" --vectors-dir vectors --pairs out/pairs.tsv --sample "$SAMPLE" --seed "$SEED" \
  --assignments "$LOCAL/assignments" --centers "$LOCAL/centers.tsv" --nprobe 1 2 4 8 16 32 \
  --out "out/recall-k$K.json"
"$PY" "$APP/cluster_purity.py" --assignments "$LOCAL/assignments" --records "records/*.jsonl" --out "out/purity-k$K.json"
echo "== $(date -Is) 완료: out/recall-k$K.json · out/purity-k$K.json"
