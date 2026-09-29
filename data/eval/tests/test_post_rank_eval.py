import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import post_rank_eval  # noqa: E402
import rank_eval  # noqa: E402
from test_rank_eval import _entry, _write_dataset, _write_vectors  # noqa: E402

# 4차원 단위 벡터 시나리오 — 손으로 계산한 개체 점수로 규칙별 순위가 갈리게 설계
#   질의 A = {A_1, A_2}, 정답 점수 = cos(A_1,A_2) = 0.8 (교차쌍 2개가 같은 값 — 규칙 불변)
#   B(2장): 쌍 코사인 {0.5, 0.9, 0.5, 0.5} → max 0.9 / mean 0.6 / mean_max 0.7 / rep1 0.5
#   C(1장): {0.85, 0.6}               → max 0.85 / mean 0.725 / mean_max 0.725 / rep1 0.85
#   → A 의 순위: max 3 · mean 1 · rep1 2 (C 만 위) · mean_max 1
#   E: 정답 사진 E_2 가 no_animal — 질의 묶음에 낀 채로 D_1(no_animal 1장 개체)과 cos 0.8
#   → max 만 B(0.85)·D(0.8) 에 밀려 rank 3, mean 은 rank 1 — "max 오염"의 축소판
VECS = {
    "A_1": [1.0, 0.0, 0.0, 0.0], "A_2": [0.8, 0.6, 0.0, 0.0],
    "B_1": [0.5, 0.166667, 0.849837, 0.0], "B_2": [0.9, -0.366667, 0.235702, 0.0],
    "C_1": [0.85, -0.133333, 0.509629, 0.0],
    "D_1": [0.0, 0.0, 0.0, 1.0],
    "E_1": [0.0, 0.0, 1.0, 0.0], "E_2": [0.0, 0.0, 0.6, 0.8],
}
MANIFEST = [
    _entry("A_1", "A", quality="good"), _entry("A_2", "A", quality="good"),
    _entry("B_1", "B"), _entry("B_2", "B"),
    _entry("C_1", "C"),
    _entry("D_1", "D", quality="no_animal"),
    _entry("E_1", "E", quality="good"), _entry("E_2", "E", quality="no_animal"),
]
PAIRS = [("pos-0000", "A_1", "A_2", 0.8), ("pos-0001", "E_1", "E_2", 0.6)]


@pytest.fixture()
def loaded(tmp_path: Path):
    dataset_dir = _write_dataset(tmp_path / "repr-t", MANIFEST, PAIRS)
    vectors_dir = _write_vectors(tmp_path / "vec", VECS)
    manifest = rank_eval.load_manifest(dataset_dir)
    pairs = rank_eval.load_pos_pairs(dataset_dir)
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [vectors_dir], None)
    return dataset_dir, vectors_dir, manifest, pairs, ids, gallery


def test_methods_rank_differently_by_impostor_aggregation(loaded) -> None:
    _, _, manifest, pairs, ids, gallery = loaded
    result, rows = post_rank_eval.evaluate(ids, gallery, manifest, pairs, [1, 2])
    a = next(r for r in rows if r["pair_id"] == "pos-0000")
    # 정답 점수는 규칙 불변(0.8) — 순위 차이는 전부 사칭자 집계에서 온다
    assert a["rank_max"] == 3 and a["rank_mean"] == 1 and a["rank_rep1"] == 2 and a["rank_mean_max"] == 1
    # no_animal 을 빼도 A 의 사칭자(B·C)는 그대로 — 순위 유지
    assert a["rank_max_xna"] == 3 and a["rank_mean_xna"] == 1 and a["rank_rep1_xna"] == 2
    # 본 지표는 no_animal 쌍(E) 제외 → n=1
    inc = result["include"]["methods"]
    assert inc["max"]["overall"]["n"] == 1 and inc["max"]["overall"]["recall@2"] == 0.0
    assert inc["mean"]["overall"]["recall@1"] == 1.0 and inc["rep1"]["overall"]["recall@2"] == 1.0
    assert inc["mean_max"]["overall"]["recall@1"] == 1.0
    assert result["include"]["candidate_entities"] == 5 and result["gallery_size"] == len(VECS)


def test_no_animal_pollutes_max_but_not_mean(loaded) -> None:
    _, _, manifest, pairs, ids, gallery = loaded
    result, rows = post_rank_eval.evaluate(ids, gallery, manifest, pairs, [1, 2])
    e = next(r for r in rows if r["pair_id"] == "pos-0001")
    assert e["no_animal"] is True
    # 포함 조건: max 는 B(0.85)·D(0.8) 에 밀려 3위, mean 은 1위 — 검사키트 오염의 축소판
    assert e["rank_max"] == 3 and e["rank_mean"] == 1 and e["rank_rep1"] == 2 and e["rank_mean_max"] == 2
    assert result["include"]["methods"]["max"]["no_animal_pairs"]["n"] == 1
    # 제외 조건: E_2 가 질의·갤러리에서 빠져 정답 유효쌍이 없다 → dropped, D 는 후보에서도 빠진다
    assert e["rank_max_xna"] is None and e["rank_mean_xna"] is None
    exc = result["exclude_no_animal"]
    assert exc["candidate_entities"] == 4 and exc["gallery_photos"] == len(VECS) - 2
    assert all(exc["methods"][m]["dropped"] == 1 for m in post_rank_eval.METHODS)
    assert exc["methods"]["max"]["no_animal_pairs"]["n"] == 0


def test_divergent_pairs_report_main_metric_spread(loaded) -> None:
    _, _, manifest, pairs, ids, gallery = loaded
    result, _ = post_rank_eval.evaluate(ids, gallery, manifest, pairs, [1, 2])
    # no_animal 쌍(E)은 정성 관찰 목록에서 빠지고, A 는 max 3 vs mean 1 로 괴리 2
    assert [d["pair_id"] for d in result["divergent_pairs"]] == ["pos-0000"]
    assert result["divergent_pairs"][0]["rank_max"] == 3 and result["divergent_pairs"][0]["rank_mean"] == 1


def test_duplicate_only_target_is_dropped(tmp_path: Path) -> None:
    # 정답 개체의 교차쌍이 전부 동일 파일(cos ≥ 0.999)이면 정답도 오답도 아니므로 탈락해야 한다
    vecs = {"G_1": [1.0, 0.0, 0.0, 0.0], "G_2": [1.0, 0.0, 0.0, 0.0], "X_1": [0.0, 1.0, 0.0, 0.0]}
    manifest_rows = [_entry("G_1", "G"), _entry("G_2", "G"), _entry("X_1", "X")]
    dataset_dir = _write_dataset(tmp_path / "d", manifest_rows, [("pos-0000", "G_1", "G_2", 1.0)])
    manifest = rank_eval.load_manifest(dataset_dir)
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [_write_vectors(tmp_path / "vec", vecs)], None)
    _, rows = post_rank_eval.evaluate(ids, gallery, manifest, rank_eval.load_pos_pairs(dataset_dir), [1])
    assert all(rows[0][f"rank_{m}"] is None for m in post_rank_eval.METHODS)


def test_single_photo_gallery_entities_keep_all_methods_comparable(tmp_path: Path) -> None:
    # 갤러리 개체가 1장이면 max=mean=mean_max=rep1 — 사진 수 편향이 집계 구현 때문이 아님을 고정
    vecs = {"A_1": [1.0, 0.0, 0.0, 0.0], "A_2": [0.8, 0.6, 0.0, 0.0], "C_1": [0.85, -0.133333, 0.509629, 0.0]}
    manifest_rows = [_entry("A_1", "A"), _entry("A_2", "A"), _entry("C_1", "C")]
    dataset_dir = _write_dataset(tmp_path / "d", manifest_rows, [("pos-0000", "A_1", "A_2", 0.8)])
    manifest = rank_eval.load_manifest(dataset_dir)
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [_write_vectors(tmp_path / "vec", vecs)], None)
    groups = post_rank_eval.build_groups(manifest, ids)
    scores = gallery @ gallery.T
    per = post_rank_eval.entity_scores(scores, [ids.index("A_1"), ids.index("A_2")], groups, "A",
                                       np.ones(len(ids), dtype=bool), ids.index("A_1"))
    assert per["max"]["C"] == pytest.approx(0.85, abs=1e-4)
    assert per["mean"]["C"] == pytest.approx((0.85 + 0.6) / 2, abs=1e-4)
    assert per["mean_max"]["C"] == pytest.approx((0.85 + 0.6) / 2, abs=1e-4)
    assert per["rep1"]["C"] == pytest.approx(0.85, abs=1e-4)
    assert all(per[m]["A"] == pytest.approx(0.8, abs=1e-4) for m in post_rank_eval.METHODS)


def test_main_writes_deterministic_json_and_tsv(tmp_path: Path, monkeypatch, capsys) -> None:
    dataset_dir = _write_dataset(tmp_path / "repr-t", MANIFEST, PAIRS)
    vectors_dir = _write_vectors(tmp_path / "vec", VECS)
    out = tmp_path / "out" / "post-agg.json"
    argv = ["post_rank_eval.py", "--dataset", str(dataset_dir), "--vectors-dir", str(vectors_dir),
            "--k", "1", "2", "--out", str(out)]
    monkeypatch.setattr(sys, "argv", argv)
    assert post_rank_eval.main() == 0
    first = out.read_text(encoding="utf-8")
    result = json.loads(first)
    assert result["include"]["methods"]["mean"]["overall"]["recall@1"] == 1.0
    ranks = (tmp_path / "out" / "post-agg.tsv").read_text(encoding="utf-8").splitlines()
    assert ranks[0].split("\t") == post_rank_eval.RANK_COLUMNS and len(ranks) == 1 + len(PAIRS)
    assert "no_animal 포함" in capsys.readouterr().out
    monkeypatch.setattr(sys, "argv", argv)
    assert post_rank_eval.main() == 0
    assert out.read_text(encoding="utf-8") == first, "같은 입력이면 바이트까지 같아야 한다 (결정적 실행)"
