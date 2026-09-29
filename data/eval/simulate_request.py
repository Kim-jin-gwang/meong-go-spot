"""사용자 요청 시늉 — 스프린트 4의 매칭 worker 가 할 일을 그대로 하며 **구간별 시간**을 잰다.

worker 가 아직 없어 실제 요청 경로를 측정할 수 없다. 이 스크립트는 그 경로의 계산 부분만 떼어
**상주 프로세스를 흉내** 낸다 — 모델과 벡터를 한 번만 올리고 요청 여러 건을 연속 처리한다.
그래서 나오는 숫자가 "두 번째 요청부터의 실제 비용"이다.

    시작 1회:  AI 모델 로딩 + 스냅샷 로딩 + 레코드 로딩
    요청마다:  사진 N장 임베딩 → 후보 필터 → 벡터 비교 → 동물별 최댓값 → 임계값 → 상위 K

대체한 것 둘 (worker 에서는 다르다):
- 후보 조회를 `records.jsonl` 로 한다. worker 는 PostgreSQL 조건 조회를 쓴다 (더 빠르다).
- 결과를 화면에 찍는다. worker 는 `match_candidate` 에 적재한다.

동물 점수 = **질의 사진 × 후보 사진 쌍 코사인의 최댓값** (계약 §3 후보 출력 정책 0-2).

사용법 (서버 2):
    python simulate_request.py --snapshot snapshot --records "records/*.jsonl" \\
        --request 411312202600543_1.jpg --request 411312202600543_1.jpg,other1.jpg,other2.jpg \\
        --photo-dir ~/demo --state 보호중 --region-prefix 서울특별시 --up-kind 417000 --happen-after 20260828 \\
        --exclude 411312202600543_1 --target 411312202600543_2 --out out/simulate.json
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
from eval_recall import load_snapshot  # noqa: E402

RECORD_FIELDS = ("processState", "orgNm", "careAddr", "happenDt", "upKindCd", "kindNm", "colorCd")


def load_records(patterns: list[str]) -> dict[str, dict]:
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


def candidate_mask(ids: list[str], records: dict[str, dict], state: list[str], regions: list[str],
                   up_kind: str | None, happen_after: str | None, exclude: set[str]) -> tuple[np.ndarray, list[tuple[str, int]]]:
    """메타데이터 조건으로 후보를 좁힌다 — worker 에서는 이 자리에 SQL 이 들어간다."""
    recs = [records.get(pid.rpartition("_")[0]) for pid in ids]
    mask = np.array([r is not None for r in recs])
    steps = [("전체", len(ids)), ("레코드 있음", int(mask.sum()))]
    if state:
        mask &= np.array([bool(r) and r["processState"] in state for r in recs])
        steps.append((f"상태 {'/'.join(state)}", int(mask.sum())))
    if regions:
        mask &= np.array([bool(r) and any(r["orgNm"].startswith(p) or r["careAddr"].startswith(p) for p in regions) for r in recs])
        steps.append((f"지역 {'/'.join(regions)}", int(mask.sum())))
    if up_kind:
        mask &= np.array([bool(r) and r["upKindCd"] == up_kind for r in recs])
        steps.append((f"축종 {up_kind}", int(mask.sum())))
    if happen_after:
        mask &= np.array([bool(r) and r["happenDt"] >= happen_after for r in recs])
        steps.append((f"발견일 ≥ {happen_after}", int(mask.sum())))
    if exclude:
        mask &= np.array([pid not in exclude for pid in ids])
        steps.append(("질의 자신 제외", int(mask.sum())))
    return mask, steps


def score_animals(query: np.ndarray, gallery: np.ndarray, cand_idx: np.ndarray,
                  ids: list[str]) -> list[tuple[str, float, str, int]]:
    """동물마다 (구조번호, 최고 점수, 그 사진ID, 그 질의 사진 번호). 쌍 코사인의 최댓값 규칙."""
    scores = query @ np.ascontiguousarray(gallery[cand_idx]).T  # (질의 장수, 후보 사진 수)
    best_q = scores.argmax(axis=0)
    best = scores.max(axis=0)
    by_animal: dict[str, tuple[float, str, int]] = {}
    for column, gallery_row in enumerate(cand_idx):
        pid = ids[gallery_row]
        no = pid.rpartition("_")[0]
        value = float(best[column])
        if value > by_animal.get(no, (-2.0, "", 0))[0]:
            by_animal[no] = (value, pid, int(best_q[column]) + 1)
    return sorted(((no, v, pid, q) for no, (v, pid, q) in by_animal.items()), key=lambda x: -x[1])


def embed(pipeline, paths: list[Path]) -> np.ndarray:
    from PIL import Image  # noqa: PLC0415

    photos = [(p.stem, Image.open(p).convert("RGB")) for p in paths]
    result = pipeline.process_photos(photos)
    rows = []
    for emb in result["embeddings"]:
        vec = np.asarray(emb["vector"], dtype=np.float32)
        rows.append(vec / max(float(np.linalg.norm(vec)), 1e-12))
    for _, image in photos:
        image.close()
    return np.vstack(rows)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="사용자 요청 시늉 — 구간별 시간 측정")
    ap.add_argument("--snapshot", required=True)
    ap.add_argument("--records", nargs="+", required=True)
    ap.add_argument("--photo-dir", required=True)
    ap.add_argument("--request", action="append", required=True, help="요청 하나의 사진 파일 목록 (쉼표 구분). 여러 번 주면 연속 처리")
    ap.add_argument("--state", nargs="*", default=[])
    ap.add_argument("--region-prefix", nargs="*", default=[])
    ap.add_argument("--up-kind")
    ap.add_argument("--happen-after")
    ap.add_argument("--exclude", nargs="*", default=[], help="후보에서 뺄 사진ID (질의 자신)")
    ap.add_argument("--target", help="정답 사진ID — 순위를 보고한다")
    ap.add_argument("--threshold", type=float, default=0.6, help="잠정 임계값 (곡선 나오기 전 임시)")
    ap.add_argument("--topk", type=int, default=20)
    ap.add_argument("--ai-dir", default=str(Path.home() / "ai"))
    ap.add_argument("--embedding-app", default=str(Path.home() / "embedding-app"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    print("═══ 시작 1회 (worker 가 뜰 때) ═══")
    sys.path.insert(0, args.embedding_app)
    from bulk_embed import load_pipeline  # noqa: PLC0415

    t = time.time()
    pipeline = load_pipeline(Path(args.ai_dir), "cpu")
    t_model = time.time() - t
    t = time.time()
    ids, gallery = load_snapshot(Path(args.snapshot))
    t_snap = time.time() - t
    t = time.time()
    records = load_records(args.records)
    t_rec = time.time() - t
    print(f"모델 {t_model:.1f}초 · 스냅샷 {t_snap:.1f}초 ({len(ids):,}장) · 레코드 {t_rec:.1f}초 ({len(records):,}건)"
          f" · 합 {t_model + t_snap + t_rec:.1f}초")

    photo_dir = Path(args.photo_dir).expanduser()
    index = {pid: i for i, pid in enumerate(ids)}
    runs = []
    for number, spec in enumerate(args.request, start=1):
        names = [n.strip() for n in spec.split(",") if n.strip()]
        paths = [photo_dir / n for n in names]
        missing = [str(p) for p in paths if not p.is_file()]
        if missing:
            raise SystemExit(f"사진 없음: {missing}")

        print(f"\n═══ 요청 {number} — 사진 {len(paths)}장 ═══")
        t = time.time()
        query = embed(pipeline, paths)
        t_embed = time.time() - t

        t = time.time()
        mask, steps = candidate_mask(ids, records, args.state, args.region_prefix,
                                     args.up_kind, args.happen_after, set(args.exclude))
        cand_idx = np.flatnonzero(mask)
        t_filter = time.time() - t

        t = time.time()
        ranked = score_animals(query, gallery, cand_idx, ids)
        passed = [r for r in ranked if r[1] >= args.threshold][:args.topk]
        t_score = time.time() - t

        total = t_embed + t_filter + t_score
        print("  후보: " + " → ".join(f"{name} {n:,}" for name, n in steps))
        print(f"  임베딩 {t_embed:.2f}초 · 필터 {t_filter:.2f}초 · 비교·집계 {t_score * 1000:.1f}ms · **계산 합 {total:.2f}초**")
        rank_of_target = None
        if args.target:
            no = args.target.rpartition("_")[0]
            for position, (animal, value, pid, q) in enumerate(ranked, start=1):
                if animal == no:
                    rank_of_target = position
                    print(f"  정답 {no}: {position}위 · 점수 {value:.4f} · 사진 {pid} · 질의 {q}번째 사진에서 최고")
                    break
            else:
                print(f"  정답 {no}: 후보에 없음 (필터에 걸림)")
        print(f"  임계값 {args.threshold} 통과 {len(passed)}마리 / 후보 {len(ranked)}마리")
        for position, (animal, value, pid, q) in enumerate(ranked[:5], start=1):
            r = records.get(animal, {})
            flag = "○" if value >= args.threshold else "×"
            print(f"   {position}. {flag} {animal} {value:.4f} ({pid}, 질의 {q}번째) — {r.get('orgNm', '')} {r.get('kindNm', '')} {r.get('colorCd', '')}")
        runs.append({"request": number, "photos": names, "candidates": len(cand_idx), "animals": len(ranked),
                     "seconds": {"embed": round(t_embed, 2), "filter": round(t_filter, 2),
                                 "score_ms": round(t_score * 1000, 1), "total": round(total, 2)},
                     "target_rank": rank_of_target, "passed": len(passed),
                     "top": [{"desertion_no": a, "score": round(v, 4), "photo_id": p, "query_photo": q} for a, v, p, q in ranked[:5]]})

    out = {"startup_seconds": {"model": round(t_model, 1), "snapshot": round(t_snap, 1), "records": round(t_rec, 1)},
           "gallery": len(ids), "filters": {"state": args.state, "region_prefix": args.region_prefix,
                                            "up_kind": args.up_kind, "happen_after": args.happen_after},
           "threshold": args.threshold, "runs": runs}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"\n기록 → {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
