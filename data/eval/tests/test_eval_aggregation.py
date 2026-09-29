import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import eval_aggregation as agg  # noqa: E402

IDS = ["A_1", "A_2", "B_1", "B_2", "C_1"]


def test_build_slots_groups_photos_by_animal_and_pads() -> None:
    animal_ids, photo_animal, slots = agg.build_slots(IDS)
    assert animal_ids == ["A", "B", "C"]
    assert photo_animal.tolist() == [0, 0, 1, 1, 2]
    assert slots.tolist() == [[0, 1], [2, 3], [4, -1]]


def _pair_scores() -> np.ndarray:
    # 질의 2장 × 개체 3 × 슬롯 2. C 의 두 번째 슬롯은 패딩(값은 무시돼야 한다).
    return np.array([
        [[0.9, 0.2], [0.5, 0.6], [0.3, 9.0]],   # 질의 0 (탐지 성공)
        [[0.1, 0.8], [0.7, 0.4], [0.95, 9.0]],  # 질의 1 (폴백)
    ], dtype=np.float32)


VALID = np.array([[True, True], [True, True], [True, False]])
SLOT_FB = np.array([[False, True], [False, False], [False, False]])


def test_rules_aggregate_pair_scores_as_specified() -> None:
    s = _pair_scores()
    q_fb = np.array([False, True])
    got = agg.score_animals(s, q_fb, SLOT_FB, VALID, "max", 1.0)
    assert np.allclose(got, [0.9, 0.7, 0.95])
    got = agg.score_animals(s, q_fb, SLOT_FB, VALID, "mean_max", 1.0)
    assert np.allclose(got, [(0.9 + 0.8) / 2, (0.6 + 0.7) / 2, (0.3 + 0.95) / 2])
    got = agg.score_animals(s, q_fb, SLOT_FB, VALID, "top2_mean", 1.0)
    assert np.allclose(got, [(0.9 + 0.8) / 2, (0.7 + 0.6) / 2, (0.95 + 0.3) / 2])


def test_fallback_rules_drop_fallback_query_photos_only_when_a_detected_one_exists() -> None:
    s = _pair_scores()
    got = agg.score_animals(s, np.array([False, True]), SLOT_FB, VALID, "fb_max", 1.0)
    assert np.allclose(got, [0.9, 0.6, 0.3])  # 질의 1(폴백) 제외 → C 의 0.95 가 사라진다
    got_all_fb = agg.score_animals(s, np.array([True, True]), SLOT_FB, VALID, "fb_max", 1.0)
    assert np.allclose(got_all_fb, [0.9, 0.7, 0.95])  # 전부 폴백이면 빼지 않는다


def test_gallery_fallback_weight_scales_only_fallback_slots() -> None:
    s = _pair_scores()
    got = agg.score_animals(s, np.array([False, False]), SLOT_FB, VALID, "max", 0.5)
    # A 의 두 번째 사진(폴백)은 0.8 → 0.4 로 줄어 A 의 max 는 0.9 유지, B·C 는 그대로
    assert np.allclose(got, [0.9, 0.7, 0.95])
    got = agg.score_animals(s, np.array([False, False]), SLOT_FB, VALID, "mean_max", 0.5)
    assert np.allclose(got, [(0.9 + 0.4) / 2, (0.6 + 0.7) / 2, (0.3 + 0.95) / 2])


def test_top2_mean_falls_back_to_single_score_when_only_one_pair_exists() -> None:
    s = np.array([[[0.4, 9.0]]], dtype=np.float32)  # 질의 1장, 개체 1, 사진 1
    got = agg.score_animals(s, np.array([False]), np.array([[False, False]]), np.array([[True, False]]), "top2_mean", 1.0)
    assert np.allclose(got, [0.4])


def test_evaluate_ranks_target_animal_and_excludes_mixed_in_animals(tmp_path: Path) -> None:
    # 3차원 단위 벡터. A_1 → A_2 가 정답쌍, B 는 A 와 비슷, C 는 멀다.
    vecs = {"A_1": [1, 0, 0], "A_2": [0.98, 0.2, 0], "B_1": [0.9, 0.43, 0], "B_2": [0.85, 0.52, 0], "C_1": [0, 0, 1], "C_2": [0, 0.1, 0.99]}
    ids = list(vecs)
    gallery = np.array([np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in vecs.values()], dtype=np.float32)
    fallback = np.zeros(len(ids), dtype=bool)
    pairs = [{"query_id": "A_1", "target_id": "A_2", "duplicate": "0"}]
    result = agg.evaluate(ids, gallery, fallback, pairs, mixes=[0], sample=0, seed=1, gallery_weight=1.0, ks=[1, 5])
    scen = result["scenarios"]["1"]["rules"]
    assert scen["max"]["recall@1"] == 1.0 and scen["mean_max"]["recall@1"] == 1.0
    assert scen["max"]["best_impostor"]["median"] < scen["max"]["target_score"]["median"]
    curve = scen["max"]["threshold_curve"]
    assert curve[0]["tau"] == 0.3 and curve[0]["recall"] == 1.0
    assert all(c["shown_mean"] <= 2 for c in curve)  # 자기 자신 제외 → A(A_2 만)·B 정도만 임계값 위
    json.dumps(result)  # 직렬화 가능


def test_evaluate_with_mixed_photos_removes_their_animals_from_the_gallery() -> None:
    vecs = {"A_1": [1, 0, 0], "A_2": [0.98, 0.2, 0], "B_1": [0, 1, 0], "B_2": [0.1, 0.99, 0], "C_1": [0, 0, 1]}
    ids = list(vecs)
    gallery = np.array([np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in vecs.values()], dtype=np.float32)
    fallback = np.zeros(len(ids), dtype=bool)
    pairs = [{"query_id": "A_1", "target_id": "A_2", "duplicate": "0"}]
    result = agg.evaluate(ids, gallery, fallback, pairs, mixes=[2], sample=0, seed=3, gallery_weight=1.0, ks=[1])
    scen = result["scenarios"]["3"]
    assert scen["query_photos"] == 3
    # 무관 사진 2장은 B·C 에서 왔고 그 개체들은 갤러리에서 빠지므로 남는 개체는 A 뿐 → 정답이 1위, 사칭자는 없음(-inf)
    assert scen["rules"]["max"]["recall@1"] == 1.0
    assert scen["rules"]["max"]["best_impostor"]["median"] == float("-inf") or scen["rules"]["max"]["threshold_curve"][0]["false_alarm"] == 0.0


def test_pool_mode_scores_only_same_species_candidates_plus_target() -> None:
    vecs = {"A_1": [1, 0, 0], "A_2": [0.98, 0.2, 0], "B_1": [0.9, 0.43, 0], "B_2": [0.85, 0.52, 0],
            "C_1": [0, 0, 1], "C_2": [0, 0.1, 0.99], "D_1": [0.99, 0.14, 0]}
    ids = list(vecs)
    gallery = np.array([np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in vecs.values()], dtype=np.float32)
    fallback = np.zeros(len(ids), dtype=bool)
    pairs = [{"query_id": "A_1", "target_id": "A_2", "duplicate": "0"}]
    kinds = {"A": "417000", "B": "417000", "C": "422400", "D": "422400"}  # D 는 A 와 매우 닮았지만 고양이 → 풀에 못 든다
    result = agg.evaluate(ids, gallery, fallback, pairs, mixes=[0], sample=0, seed=5, gallery_weight=1.0, ks=[1],
                          pool=1, up_kind_of=kinds)
    assert result["candidate_pool"] == 1
    rule = result["scenarios"]["1"]["rules"]["max"]
    assert rule["recall@1"] == 1.0  # 후보는 B 하나 + 정답 A → A_2 가 1위
    assert abs(rule["best_impostor"]["median"] - float(gallery[0] @ gallery[2])) < 1e-4  # 사칭자 = B_1
    assert rule["threshold_curve"][0]["shown_mean"] == 2.0


def test_pool_mode_caps_at_the_available_same_species_animals() -> None:
    # 개는 A·B 두 마리뿐인데 pool=10 을 요구 — 있는 만큼(B 하나)만 뽑고 끝나야 한다 (무한 루프 회귀 방지, AI 리뷰 !173)
    vecs = {"A_1": [1, 0, 0], "A_2": [0.98, 0.2, 0], "B_1": [0.9, 0.43, 0], "C_1": [0, 0, 1], "C_2": [0, 0.1, 0.99]}
    ids = list(vecs)
    gallery = np.array([np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in vecs.values()], dtype=np.float32)
    fallback = np.zeros(len(ids), dtype=bool)
    pairs = [{"query_id": "A_1", "target_id": "A_2", "duplicate": "0"}]
    kinds = {"A": "417000", "B": "417000", "C": "422400"}
    result = agg.evaluate(ids, gallery, fallback, pairs, mixes=[0], sample=0, seed=7, gallery_weight=1.0, ks=[1],
                          pool=10, up_kind_of=kinds)
    rule = result["scenarios"]["1"]["rules"]["max"]
    assert rule["recall@1"] == 1.0
    assert rule["threshold_curve"][0]["shown_mean"] == 2.0  # 후보 B + 정답 A
