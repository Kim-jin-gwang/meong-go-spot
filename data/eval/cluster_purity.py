"""K-Means 배정의 해석 — 클러스터가 축종(upKindCd)·품종(kindCd)을 얼마나 모아 담는지 (purity) 와 크기 분포.

purity = Σ_클러스터 (최다 라벨 건수) / 전체 배정 건수. 라벨이 없는 사진(레코드 없음)은 제외.
비교 기준으로 같은 크기 분포의 무작위 배정 purity(=라벨 편중만 반영)도 함께 낸다 — 이보다 높아야 클러스터가 의미가 있다.

    python cluster_purity.py --assignments <part-* 디렉터리|파일> --records "<records.jsonl 글롭>" --out purity.json
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path

from eval_recall import load_assignments
from make_pairs import load_records, split_id


def purity(assignments: dict[str, int], label_of: dict[str, str]) -> dict:
    by_cluster: dict[int, Counter] = defaultdict(Counter)
    labelled = 0
    for pid, cid in assignments.items():
        label = label_of.get(pid)
        if label:
            by_cluster[cid][label] += 1
            labelled += 1
    if not labelled:  # 레코드와 배정 ID 가 하나도 맞지 않음 — 키를 다 채워 출력부가 죽지 않게, 값은 0 으로 드러낸다
        return {"n": 0, "clusters": 0, "labels": 0, "purity": 0.0, "majority_label_share": 0.0, "top_labels": []}
    dominant = sum(c.most_common(1)[0][1] for c in by_cluster.values())
    overall = Counter()
    for c in by_cluster.values():
        overall.update(c)
    majority_share = overall.most_common(1)[0][1] / labelled  # 라벨 하나만 찍어도 얻는 purity 하한
    return {"n": labelled, "clusters": len(by_cluster), "labels": len(overall), "purity": round(dominant / labelled, 4),
            "majority_label_share": round(majority_share, 4),
            "top_labels": [{"label": k, "share": round(v / labelled, 4)} for k, v in overall.most_common(5)]}


def random_purity(assignments: dict[str, int], label_of: dict[str, str], seed: int) -> float:
    """같은 클러스터 크기 분포에 라벨을 무작위로 섞었을 때의 purity — 우연히 얻는 수준."""
    items = [(cid, label_of[pid]) for pid, cid in assignments.items() if label_of.get(pid)]
    if not items:
        return 0.0
    labels = [label for _, label in items]
    random.Random(seed).shuffle(labels)
    by_cluster: dict[int, Counter] = defaultdict(Counter)
    for (cid, _), label in zip(items, labels):
        by_cluster[cid][label] += 1
    return round(sum(c.most_common(1)[0][1] for c in by_cluster.values()) / len(items), 4)


def size_stats(assignments: dict[str, int]) -> dict:
    sizes = sorted(Counter(assignments.values()).values())
    return {"clusters": len(sizes), "min": sizes[0], "median": sizes[len(sizes) // 2], "max": sizes[-1],
            "p90": sizes[int(len(sizes) * 0.9) - 1] if len(sizes) >= 10 else sizes[-1], "total": sum(sizes)}


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="K-Means 클러스터의 축종·품종 purity")
    ap.add_argument("--assignments", required=True)
    ap.add_argument("--records", nargs="+", required=True, help="records.jsonl 글롭")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    assignments = load_assignments(Path(args.assignments))
    records = load_records(args.records)

    def labels(field: str) -> dict[str, str]:
        out = {}
        for pid in assignments:
            parts = split_id(pid)
            if parts and parts[0] in records and records[parts[0]][field]:
                out[pid] = records[parts[0]][field]
        return out

    result = {"assignments": str(args.assignments), "size": size_stats(assignments)}
    for name, field in (("up_kind", "up_kind_cd"), ("kind", "kind_cd")):
        label_of = labels(field)
        result[name] = {**purity(assignments, label_of), "random_purity": random_purity(assignments, label_of, args.seed)}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    s = result["size"]
    print(f"배정 {s['total']:,} · 클러스터 {s['clusters']} · 크기 min/중앙/p90/max {s['min']}/{s['median']}/{s['p90']}/{s['max']}")
    for name in ("up_kind", "kind"):
        r = result[name]
        print(f"  {name}: purity {r['purity']:.3f} (무작위 {r['random_purity']:.3f}, 최다 라벨 비중 {r['majority_label_share']:.3f}, 라벨 {r['labels']}종, n={r['n']:,})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
