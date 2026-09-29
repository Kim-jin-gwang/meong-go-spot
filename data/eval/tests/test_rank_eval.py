import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import rank_eval  # noqa: E402


def _entry(pid: str, group: str, *, species: str = "개", fallback: bool = False,
           angle: str | None = None, background: str | None = None, quality: str | None = None) -> dict:
    return {"photo_id": pid, "group_id": group, "species": species, "fallback": fallback,
            "tags": {"angle": angle, "background": background, "quality": quality}}


def _write_dataset(d: Path, manifest: list[dict], pairs: list[tuple[str, str, str, float]]) -> Path:
    d.mkdir(parents=True, exist_ok=True)
    (d / "manifest.jsonl").write_text("".join(json.dumps(e, ensure_ascii=False) + "\n" for e in manifest),
                                      encoding="utf-8")
    with (d / "pairs.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        fp.write("pair_id\tpair_type\tlabel\tquery_id\ttarget_id\tcos_v2\n")
        for pid, q, t, cos in pairs:
            fp.write(f"{pid}\tpos\t1\t{q}\t{t}\t{cos}\n")
        fp.write("neg-0000\tneg_easy\t0\tA_1\tD_1\t0.1\n")  # 음성 쌍은 읽히지 않아야 한다
    return d


def _write_vectors(d: Path, rows: dict[str, list[float]]) -> Path:
    d.mkdir(parents=True, exist_ok=True)
    with (d / "vectors-images-202407-0000.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        for pid, v in rows.items():
            fp.write(pid + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
    return d


# 3차원 단위 벡터 시나리오
# A: A_1↔A_2 가깝다 → rank 1 (easy 밴드)
# B: B_1 에서 정답 B_2(cos 0.6)보다 폴백 사진 D_1(cos 0.8)이 가깝다 → rank 2, 폴백 제외 갤러리에서는 rank 1
# C: C_2 가 C_1 과 동일 파일(cos 1.0) → 정답 전무로 탈락(dropped_all_duplicates)
# F: 정답 F_2 가 폴백 → 폴백 제외 갤러리에서 정답 유실
# E: 정답 E_2 가 no_animal → 본 지표 제외, 별도 표
VECS = {
    "A_1": [1.0, 0.0, 0.0], "A_2": [0.98, 0.2, 0.0],
    "B_1": [0.0, 1.0, 0.0], "B_2": [0.0, 0.6, 0.8],
    "C_1": [0.0, 0.0, 1.0], "C_2": [0.0, 0.0, 1.0],
    "D_1": [0.0, 0.8, 0.6],
    "F_1": [0.6, 0.0, 0.8], "F_2": [0.8, 0.0, 0.6],
    "E_1": [-1.0, 0.0, 0.0], "E_2": [-0.9, -0.1, 0.0],
}
MANIFEST = [
    _entry("A_1", "A", angle="front", background="cage", quality="good"),
    _entry("A_2", "A", angle="front", background="cage", quality="good"),
    _entry("B_1", "B", species="고양이", angle="side", quality="dark"),
    _entry("B_2", "B", species="고양이", angle="side", quality="dark"),
    _entry("C_1", "C"), _entry("C_2", "C"),
    _entry("D_1", "D", fallback=True),
    _entry("F_1", "F"), _entry("F_2", "F", fallback=True),
    _entry("E_1", "E"), _entry("E_2", "E", quality="no_animal"),
]
PAIRS = [("pos-0000", "A_1", "A_2", 0.9), ("pos-0001", "B_1", "B_2", 0.5),
         ("pos-0002", "C_1", "C_2", 1.0), ("pos-0003", "F_1", "F_2", 0.7),
         ("pos-0004", "E_1", "E_2", 0.9)]


@pytest.fixture()
def dataset(tmp_path: Path):
    dataset_dir = _write_dataset(tmp_path / "repr-t", MANIFEST, PAIRS)
    vectors_dir = _write_vectors(tmp_path / "vec", VECS)
    return dataset_dir, vectors_dir


def test_evaluate_ranks_bands_tags_and_fallback_tracks(dataset) -> None:
    dataset_dir, vectors_dir = dataset
    manifest = rank_eval.load_manifest(dataset_dir)
    pairs = rank_eval.load_pos_pairs(dataset_dir)
    assert [p["pair_id"] for p in pairs] == [p[0] for p in PAIRS], "음성 쌍이 섞이면 안 된다"
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [vectors_dir], None)
    assert ids == sorted(VECS) and np.allclose(np.linalg.norm(gallery, axis=1), 1.0, atol=1e-5)

    result, rows = rank_eval.evaluate(ids, gallery, manifest, pairs, [1, 2])
    by = {r["pair_id"]: r for r in rows}
    # A: rank 1 · B: D_1 이 더 가까워 rank 2 · C: 동일 파일뿐이라 탈락 · E: no_animal 제외
    assert by["pos-0000"]["rank"] == 1 and by["pos-0001"]["rank"] == 2
    assert by["pos-0002"]["rank"] is None and result["dropped_all_duplicates"] == 1
    assert result["no_animal_excluded"] == 1 and result["no_animal_pairs"][0]["pair_id"] == "pos-0004"
    assert result["no_animal_pairs"][0]["rank"] >= 1
    # 본 지표 = A(1)·B(2)·F(1) — F_1 의 최근접은 F_2
    o = result["overall"]
    assert o["n"] == 3 and o["recall@1"] == round(2 / 3, 4) and o["recall@2"] == 1.0
    assert o["mrr"] == round((1 + 0.5 + 1) / 3, 4)
    # 밴드: A=easy(0.9), B=hard(0.5), F=mid(0.7)
    assert result["by_band"]["easy"]["n"] == 1 and result["by_band"]["hard"]["recall@1"] == 0.0
    assert result["by_band"]["mid"]["recall@1"] == 1.0
    # 축종·태그 분해 (질의 쪽/정답 쪽)
    assert result["by_species"]["고양이"]["recall@1"] == 0.0 and result["by_species"]["개"]["n"] == 2
    assert result["by_query_tag"]["angle"]["side"]["recall@1"] == 0.0
    assert result["by_target_tag"]["quality"]["dark"]["n"] == 1
    # 폴백 패턴: F 는 one_fallback
    assert result["by_fallback_pair"]["one_fallback"]["n"] == 1
    # 폴백 제외 갤러리: D_1·F_2 빠짐 → B 는 rank 1 로 회복, F 는 정답 유실
    fb = result["fallback_gallery_excluded"]
    assert fb["gallery_size"] == len(VECS) - 2 and fb["dropped_fallback_target"] == 1
    assert by["pos-0001"]["rank_nofb"] == 1 and by["pos-0003"]["rank_nofb"] is None
    assert fb["overall"]["n"] == 2 and fb["overall"]["recall@1"] == 1.0


def test_duplicate_candidate_does_not_push_real_target_down(tmp_path: Path) -> None:
    # 같은 그룹에 동일 파일(g_2)과 진짜 정답(g_3)이 함께 있으면 동일 파일은 후보에서 통째로 빠져야 한다
    vecs = {"g_1": [1.0, 0.0, 0.0], "g_2": [1.0, 0.0, 0.0], "g_3": [0.9, 0.436, 0.0], "x_1": [0.0, 1.0, 0.0]}
    manifest_rows = [_entry("g_1", "G"), _entry("g_2", "G"), _entry("g_3", "G"), _entry("x_1", "X")]
    dataset_dir = _write_dataset(tmp_path / "d", manifest_rows, [("pos-0000", "g_1", "g_3", 0.9)])
    vectors_dir = _write_vectors(tmp_path / "vec", vecs)
    manifest = rank_eval.load_manifest(dataset_dir)
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [vectors_dir], None)
    ranked = rank_eval.rank_pairs(ids, gallery, manifest, rank_eval.load_pos_pairs(dataset_dir))
    assert ranked == [{"pair_id": "pos-0000", "rank": 1, "dropped": False}]


def test_missing_vector_fails_instead_of_silently_shrinking(dataset, tmp_path: Path) -> None:
    dataset_dir, _ = dataset
    partial = _write_vectors(tmp_path / "partial", {k: v for k, v in VECS.items() if k != "B_2"})
    manifest = rank_eval.load_manifest(dataset_dir)
    with pytest.raises(SystemExit) as error:
        rank_eval.load_dataset_vectors(manifest, [partial], None)
    assert "벡터 없음 1장" in str(error.value)


def test_malformed_cos_v2_fails_with_clear_message(tmp_path: Path) -> None:
    # 밴드 분해가 cos_v2 에 걸려 있다 — 결함 값은 traceback 이 아니라 어느 쌍인지 알려주며 종료해야 한다
    dataset_dir = _write_dataset(tmp_path / "d", [_entry("g_1", "G"), _entry("g_2", "G")],
                                 [("pos-0000", "g_1", "g_2", "abc")])
    with pytest.raises(SystemExit) as error:
        rank_eval.load_pos_pairs(dataset_dir)
    assert "pos-0000" in str(error.value) and "cos_v2" in str(error.value)


def test_load_dataset_vectors_requires_a_source() -> None:
    with pytest.raises(ValueError):
        rank_eval.load_dataset_vectors({"a_1": {}}, None, None)
    with pytest.raises(ValueError):
        rank_eval.load_dataset_vectors({"a_1": {}}, [], None)


def test_summarize_handles_empty_bucket_without_nan_crash(tmp_path: Path) -> None:
    # 예: 정답이 전부 폴백인 데이터셋 — 폴백 제외 조건에서 평가할 쌍이 0이어도 죽지 않아야 한다
    assert rank_eval.summarize([], [1, 5]) == {"recall@1": None, "recall@5": None, "mrr": None, "n": 0, "median_rank": None}
    vecs = {"g_1": [1.0, 0.0, 0.0], "g_2": [0.98, 0.2, 0.0]}
    manifest_rows = [_entry("g_1", "G"), _entry("g_2", "G", fallback=True)]
    dataset_dir = _write_dataset(tmp_path / "d", manifest_rows, [("pos-0000", "g_1", "g_2", 0.9)])
    manifest = rank_eval.load_manifest(dataset_dir)
    ids, gallery = rank_eval.load_dataset_vectors(manifest, [_write_vectors(tmp_path / "vec", vecs)], None)
    result, _ = rank_eval.evaluate(ids, gallery, manifest, rank_eval.load_pos_pairs(dataset_dir), [1, 5])
    fb = result["fallback_gallery_excluded"]
    assert fb["dropped_fallback_target"] == 1 and fb["overall"] == rank_eval.summarize([], [1, 5])


def test_main_writes_deterministic_json_and_tsv(dataset, tmp_path: Path, monkeypatch, capsys) -> None:
    dataset_dir, vectors_dir = dataset
    out = tmp_path / "out" / "rank.json"
    argv = ["rank_eval.py", "--dataset", str(dataset_dir), "--vectors-dir", str(vectors_dir),
            "--k", "1", "2", "--out", str(out)]
    monkeypatch.setattr(sys, "argv", argv)
    assert rank_eval.main() == 0
    first = out.read_text(encoding="utf-8")
    result = json.loads(first)
    assert result["pairs_evaluated"] == 3 and result["overall"]["recall@2"] == 1.0
    ranks = (tmp_path / "out" / "rank.tsv").read_text(encoding="utf-8").splitlines()
    assert ranks[0].split("\t") == rank_eval.RANK_COLUMNS and len(ranks) == 1 + len(PAIRS)
    assert "no_animal pos-0004" in capsys.readouterr().out
    monkeypatch.setattr(sys, "argv", argv)
    assert rank_eval.main() == 0
    assert out.read_text(encoding="utf-8") == first, "같은 입력이면 바이트까지 같아야 한다 (결정적 실행)"
