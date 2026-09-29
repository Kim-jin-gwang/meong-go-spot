"""사진 1장 → 임베딩 → (메타데이터 필터) → K-Means 색인으로 후보 축소 → Top-K. 오늘 만든 경로가 실제로 이어지는지 보는 시연.

    python demo_query.py --image query.jpg --snapshot snapshot --centers index-k256/centers.tsv \
        --assignments index-k256/assignments --nprobe 16 --topk 5 --exclude-id 4113..._1 --out out/demo.json
    # 제품 조건 재현: 보호중 + 실종 지역(관할 시도) + 축종 + 발견일 이후
    python demo_query.py ... --records "records/*.jsonl" --state 보호중 --region-prefix 서울특별시 --up-kind 417000 --happen-after 20260801

정확 탐색(갤러리 전체 내적)의 Top-K 도 함께 내서 색인 결과와 비교한다. 서버 2 (~/ai/.venv, CPU) 에서 실행.
필터는 벡터 검색이 아니라 레코드 메타데이터(processState·orgNm·upKindCd·happenDt)로 거른다 — 제품에서는 SQL/쎄타조인 단계.
"""

from __future__ import annotations

import argparse
import glob
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_recall import load_assignments, load_centers, load_gallery, load_snapshot  # noqa: E402

RECORD_FIELDS = ("processState", "orgNm", "careAddr", "careNm", "happenDt", "upKindCd", "kindNm", "colorCd")


def embed_image(ai_dir: Path, embedding_app: Path, image: Path) -> tuple[np.ndarray, dict]:
    sys.path.insert(0, str(embedding_app))
    from PIL import Image  # noqa: PLC0415
    from bulk_embed import load_pipeline  # noqa: PLC0415

    t0 = time.time()
    pipeline = load_pipeline(ai_dir, "cpu")
    t_load = time.time() - t0
    t1 = time.time()
    result = pipeline.process_photos([("query", Image.open(image).convert("RGB"))])
    t_embed = time.time() - t1
    emb = result["embeddings"][0]
    vec = np.asarray(emb["vector"], dtype=np.float32)
    vec /= max(float(np.linalg.norm(vec)), 1e-12)
    return vec, {"detection": emb.get("detection"), "model": result.get("model"), "load_seconds": round(t_load, 1),
                 "embed_seconds": round(t_embed, 2)}


def load_record_fields(patterns: list[str]) -> dict[str, dict]:
    """desertionNo → 필터에 쓰는 필드. 같은 개체가 여러 월에 있으면 마지막(최신 상태)."""
    out: dict[str, dict] = {}
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            with open(path, encoding="utf-8") as fp:
                for line in fp:
                    r = json.loads(line)
                    no = str(r.get("desertionNo") or "")
                    if no:
                        out[no] = {k: (r.get(k) or "") for k in RECORD_FIELDS}
    return out


def metadata_mask(ids: list[str], records: dict[str, dict], states: list[str], regions: list[str], up_kind: str | None,
                  happen_after: str | None) -> tuple[np.ndarray, list[tuple[str, int]]]:
    """필터를 하나씩 겹치며 남는 수를 기록한다 — '어느 조건이 얼마나 줄이는가' 를 보여주기 위해."""
    nos = [pid.rpartition("_")[0] for pid in ids]
    recs = [records.get(no) for no in nos]
    mask = np.array([r is not None for r in recs])
    steps = [("레코드 있음", int(mask.sum()))]
    if states:
        mask &= np.array([bool(r) and r["processState"] in states for r in recs])
        steps.append((f"상태 {'/'.join(states)}", int(mask.sum())))
    if regions:
        mask &= np.array([bool(r) and any(r["orgNm"].startswith(p) or r["careAddr"].startswith(p) for p in regions) for r in recs])
        steps.append((f"지역 {'/'.join(regions)}", int(mask.sum())))
    if up_kind:
        mask &= np.array([bool(r) and r["upKindCd"] == up_kind for r in recs])
        steps.append((f"축종 {up_kind}", int(mask.sum())))
    if happen_after:
        mask &= np.array([bool(r) and r["happenDt"] >= happen_after for r in recs])
        steps.append((f"발견일 ≥ {happen_after}", int(mask.sum())))
    return mask, steps


def nearest_centers(q: np.ndarray, centers: np.ndarray, nprobe: int) -> np.ndarray:
    affinity = centers @ q - 0.5 * (centers ** 2).sum(axis=1)  # argmin ‖q−c‖² — K-Means 와 같은 배정
    return np.argsort(-affinity)[:nprobe]


def top_k(scores: np.ndarray, k: int) -> list[int]:
    if len(scores) == 0:
        return []
    idx = np.argpartition(-scores, min(k, len(scores) - 1))[:k]
    return [int(i) for i in idx[np.argsort(-scores[idx])] if np.isfinite(scores[i])]


def describe(pid: str, records: dict[str, dict]) -> str:
    r = records.get(pid.rpartition("_")[0])
    if not r:
        return ""
    return f"{r['processState']} · {r['orgNm']} · 발견 {r['happenDt']} · {r['kindNm']} {r['colorCd']}"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="사진 1장 → (필터) → 색인 검색 시연")
    ap.add_argument("--image", required=True)
    ap.add_argument("--vectors-dir", help="벡터 TSV 디렉터리 (파싱 71초)")
    ap.add_argument("--snapshot", help="index_tools.py snapshot 디렉터리 — 이진 로딩 0.5초. 둘 중 하나만 준다")
    ap.add_argument("--centers", required=True)
    ap.add_argument("--assignments", required=True)
    ap.add_argument("--nprobe", type=int, default=16)
    ap.add_argument("--topk", type=int, default=5)
    ap.add_argument("--exclude-id", help="질의 사진 자신의 갤러리 ID (있으면 결과에서 제외해 '짝 찾기' 를 본다)")
    ap.add_argument("--target-id", help="정답(같은 개체의 다른 사진) 갤러리 ID — 정확·색인 탐색에서의 순위를 보고한다")
    ap.add_argument("--records", nargs="*", default=[], help="records.jsonl 글롭 — 필터·후보 설명에 사용")
    ap.add_argument("--state", nargs="*", default=[], help="processState 허용값 (예: 보호중)")
    ap.add_argument("--region-prefix", nargs="*", default=[], help="orgNm/careAddr 접두 (예: 서울특별시)")
    ap.add_argument("--up-kind", help="upKindCd (417000 개 · 422400 고양이 · 429900 기타)")
    ap.add_argument("--happen-after", help="happenDt 하한 YYYYMMDD (실종일 이후 발견)")
    ap.add_argument("--ai-dir", default=str(Path.home() / "ai"))
    ap.add_argument("--embedding-app", default=str(Path.home() / "embedding-app"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if bool(args.vectors_dir) == bool(args.snapshot):
        ap.error("--vectors-dir 과 --snapshot 중 정확히 하나를 줘야 합니다")

    q, info = embed_image(Path(args.ai_dir), Path(args.embedding_app), Path(args.image))
    det = info["detection"] or {}
    detected = "폴백(원본 전체)" if det.get("fallback") else f"신뢰도 {det.get('confidence')}"
    print(f"1) 임베딩: 모델 로딩 {info['load_seconds']}초 · 추론 {info['embed_seconds']}초 · 차원 {len(q)} · 탐지 {detected}")

    t0 = time.time()
    ids, gallery = load_snapshot(Path(args.snapshot)) if args.snapshot else load_gallery(Path(args.vectors_dir))
    center_ids, centers = load_centers(Path(args.centers))
    assignments = load_assignments(Path(args.assignments))
    labels = np.array([assignments.get(pid, -1) for pid in ids])
    records = load_record_fields(args.records) if args.records else {}
    print(f"2) 갤러리 {len(ids):,}개 · 중심 {len(center_ids)}개 · 레코드 {len(records):,}건 · 로딩 {time.time() - t0:.0f}초")

    exclude = np.zeros(len(ids), dtype=bool)
    if args.exclude_id and args.exclude_id in ids:
        exclude[ids.index(args.exclude_id)] = True
        print(f"   질의 자신의 갤러리 벡터와 코사인 {float(gallery[ids.index(args.exclude_id)] @ q):.4f} (GPU 벌크 vs 지금 CPU 추론)")

    allowed = ~exclude
    steps: list[tuple[str, int]] = []
    filtering = bool(args.state or args.region_prefix or args.up_kind or args.happen_after)
    if filtering:
        if not records:
            ap.error("필터를 쓰려면 --records 가 필요합니다")
        meta, steps = metadata_mask(ids, records, args.state, args.region_prefix, args.up_kind, args.happen_after)
        allowed &= meta
        print("3) 메타데이터 필터 (제품에서는 SQL/쎄타조인 단계): 전체 " + f"{len(ids):,}" + " → "
              + " → ".join(f"{name} {n:,}" for name, n in steps))
    n_allowed = int(allowed.sum())

    # 정확 탐색: 허용된 후보 전체 내적
    allowed_idx = np.flatnonzero(allowed)
    t2 = time.time()
    exact_scores_sub = gallery[allowed_idx] @ q
    t_exact = time.time() - t2
    exact_scores = np.full(len(ids), -np.inf, dtype=np.float32)
    exact_scores[allowed_idx] = exact_scores_sub
    idx_exact = top_k(exact_scores, args.topk)

    # 색인 경로: 중심 nprobe 개 → 그 클러스터 ∩ 허용 후보만 내적
    probe = nearest_centers(q, centers, args.nprobe)
    probe_ids = center_ids[probe]
    cand_idx = np.flatnonzero(np.isin(labels, probe_ids) & allowed)
    cand_vectors = np.ascontiguousarray(gallery[cand_idx])
    t1 = time.time()
    cand_scores = cand_vectors @ q
    t_probe = time.time() - t1
    probe_scores = np.full(len(ids), -np.inf, dtype=np.float32)
    probe_scores[cand_idx] = cand_scores
    idx_probe = top_k(probe_scores, args.topk)

    step = 4 if filtering else 3
    print(f"{step}) 색인: 최근접 클러스터 {args.nprobe}개 → 후보 {len(cand_idx):,}개 ({len(cand_idx) / max(len(ids), 1):.1%} of 갤러리"
          + (f", 필터 통과 {n_allowed:,}개 중 {len(cand_idx) / max(n_allowed, 1):.0%}" if filtering else "")
          + f") · 후보 내적 {t_probe * 1000:.1f}ms  vs  필터 통과 전체 내적 {t_exact * 1000:.1f}ms ({n_allowed:,}개)")
    target_info = None
    if args.target_id and args.target_id in ids:
        ti = ids.index(args.target_id)
        if allowed[ti]:
            exact_rank = int((exact_scores > exact_scores[ti]).sum()) + 1
            in_probe = bool(np.isin(labels[ti], probe_ids))
            # 같은 배열 안에서 비교한다 — 부분 행렬 내적은 BLAS 누적 순서가 달라 마지막 자리에서 어긋나 자기 자신을 '더 좋은 후보' 로 셀 수 있다
            probe_rank = int((cand_scores > cand_scores[np.flatnonzero(cand_idx == ti)[0]]).sum()) + 1 if in_probe else None
            target_info = {"id": args.target_id, "cos": round(float(exact_scores[ti]), 4), "cluster": int(labels[ti]),
                           "exact_rank": exact_rank, "in_probe": in_probe, "probe_rank": probe_rank}
            print(f"   정답(짝) {args.target_id}: 코사인 {exact_scores[ti]:.4f} · c{labels[ti]} · 정확 순위 {exact_rank} · "
                  f"색인 {'순위 ' + str(probe_rank) if in_probe else '범위 밖'}")
        else:
            target_info = {"id": args.target_id, "filtered_out": True}
            print(f"   정답(짝) {args.target_id}: 필터에 걸려 후보 아님 ({describe(args.target_id, records)})")

    print(f"{step + 1}) Top-{args.topk} (색인)  vs  Top-{args.topk} (정확 탐색)")
    rows = []
    for rank in range(args.topk):
        a = idx_probe[rank] if rank < len(idx_probe) else None
        b = idx_exact[rank] if rank < len(idx_exact) else None
        ra = f"{ids[a]:>22} cos {exact_scores[a]:.4f} c{labels[a]}" if a is not None else "-"
        rb = f"{ids[b]:>22} cos {exact_scores[b]:.4f} c{labels[b]}" if b is not None else "-"
        print(f"   {rank + 1}. {ra}   |   {rb}   {'=' if a == b else '≠'}")
        if b is not None and records:
            print(f"      {describe(ids[b], records)}")
        rows.append({"rank": rank + 1,
                     "probe": {"id": ids[a], "cos": round(float(exact_scores[a]), 4), "cluster": int(labels[a]), "record": records.get(ids[a].rpartition('_')[0])} if a is not None else None,
                     "exact": {"id": ids[b], "cos": round(float(exact_scores[b]), 4), "cluster": int(labels[b]), "record": records.get(ids[b].rpartition('_')[0])} if b is not None else None})
    same = sum(1 for a, b in zip(idx_probe, idx_exact) if a == b)
    print(f"   일치 {same}/{args.topk}")

    out = {"image": args.image, "embedding": info, "gallery": len(ids), "filters": {"state": args.state, "region_prefix": args.region_prefix,
           "up_kind": args.up_kind, "happen_after": args.happen_after, "steps": steps, "allowed": n_allowed},
           "nprobe": args.nprobe, "probe_clusters": probe_ids.tolist(), "candidates": len(cand_idx),
           "probe_ms": round(t_probe * 1000, 1), "exact_ms": round(t_exact * 1000, 1), "topk": rows, "agreement": same, "target": target_info}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
