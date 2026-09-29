"""check_vocab_coverage 정규화·집계 테스트 — 실제 vocabulary.json으로 규칙을 검증한다."""

import json
from pathlib import Path

from tools import check_vocab_coverage

VOCAB_PATH = Path(__file__).resolve().parents[2] / "tags" / "vocabulary.json"


def _vocab():
    return json.loads(VOCAB_PATH.read_text(encoding="utf-8"))


def test_color_normalization_rules():
    color_map, _ = check_vocab_coverage.build_lookups(_vocab())

    # 규칙 1: '기타(...)' 래퍼 제거 — 괄호 앞 공백 변형 포함
    codes, unmapped = check_vocab_coverage.color_codes("기타(흰색)", color_map)
    assert codes == {"white"} and unmapped == []
    codes, unmapped = check_vocab_coverage.color_codes("기타 (흰색)", color_map)
    assert codes == {"white"} and unmapped == []

    # 규칙 2: 복합 표기 분리
    codes, _ = check_vocab_coverage.color_codes("갈색&흰색", color_map)
    assert codes == {"brown", "white"}

    # 규칙 4: 복합 단일 토큰 확장
    codes, _ = check_vocab_coverage.color_codes("흑백", color_map)
    assert codes == {"black", "white"}

    # 규칙 5: 수식어(ignore_tokens)는 실패로 세지 않고 버린다
    codes, unmapped = check_vocab_coverage.color_codes("옅은 황갈색", color_map)
    assert codes == {"tan"} and unmapped == []

    # 규칙 6: 매핑 실패 토큰은 코드 없이 unmapped로 남는다
    codes, unmapped = check_vocab_coverage.color_codes("무지개색", color_map)
    assert codes == set() and unmapped == ["무지개색"]


def test_breed_lookup_uses_label_and_aliases():
    _, breed_map = check_vocab_coverage.build_lookups(_vocab())
    assert breed_map[check_vocab_coverage.norm("말티즈")] == "k000072"
    assert breed_map[check_vocab_coverage.norm("몰티즈")] == "k000072"  # 음차 별칭
    assert breed_map[check_vocab_coverage.norm("골든 리트리버")] == breed_map[
        check_vocab_coverage.norm("골든리트리버")
    ]  # 띄어쓰기 변형


def test_size_thresholds():
    assert check_vocab_coverage.size_code("3.2(Kg)") == "small"
    assert check_vocab_coverage.size_code("8(Kg)") == "medium"
    assert check_vocab_coverage.size_code("20(Kg)") == "large"
    assert check_vocab_coverage.size_code("미상") is None
    assert check_vocab_coverage.size_code("0(Kg)") is None  # 미측정 관례 — small 오분류 방지


def test_check_aggregates_record_level_coverage():
    records = [
        json.dumps(r, ensure_ascii=False)
        for r in [
            {"kindNm": "말티즈", "colorCd": "흰색", "weight": "3(Kg)"},
            {"kindNm": "이상한품종", "colorCd": "흰색&무지개색", "weight": "?(Kg)"},
        ]
    ]
    result = check_vocab_coverage.check(records, _vocab())
    assert result["record_count"] == 2
    assert result["parse_errors"] == 0
    assert result["breed"]["mapped"] == 1
    assert result["breed"]["unmapped_top"] == {"이상한품종": 1}
    assert result["color"]["records_all_tokens_mapped"] == 1
    assert result["color"]["records_partially_mapped"] == 1
    assert result["color"]["record_tag_rate"] == 1.0  # 두 건 모두 태그는 생성됨
    assert result["size"]["mapped"] == 1


def test_broken_jsonl_line_is_counted_not_fatal():
    records = ["{broken json", '{"kindNm": "말티즈", "colorCd": "흰색", "weight": "3(Kg)"}']
    result = check_vocab_coverage.check(records, _vocab())
    assert result["record_count"] == 1
    assert result["parse_errors"] == 1
