"""tag_value_survey 집계 로직 테스트 — 네트워크·HDFS 없이 문자열 입력만으로 돈다."""

import json

from tools import tag_value_survey


def _lines(*records):
    return [json.dumps(r, ensure_ascii=False) for r in records]


def test_survey_counts_values_and_missing():
    result = tag_value_survey.survey(
        _lines(
            {"upKindNm": "개", "kindNm": "말티즈", "colorCd": "흰색", "weight": "3(Kg)"},
            {"upKindNm": "개", "kindNm": "몰티즈", "colorCd": "흰색&갈색", "weight": "3.5(Kg)"},
            {"upKindNm": "고양이", "kindNm": "", "colorCd": None, "weight": "미상"},
        )
    )
    assert result["record_count"] == 3
    assert result["species"] == {"개": 2, "고양이": 1}
    assert result["breeds"] == {"말티즈": 1, "몰티즈": 1}
    assert result["missing"]["kindNm"] == 1
    assert result["missing"]["colorCd"] == 1
    assert result["missing_rate"]["colorCd"] == round(1 / 3, 4)


def test_color_tokens_split_compound_values():
    result = tag_value_survey.survey(
        _lines(
            {"colorCd": "흰색&갈색"},
            {"colorCd": "흰, 갈색"},
            {"colorCd": "검/흰"},
        )
    )
    assert result["color_tokens_top"]["갈색"] == 2
    assert result["color_tokens_top"]["흰색"] == 1
    assert result["color_tokens_top"]["흰"] == 2


def test_weight_binning_and_unparsed():
    result = tag_value_survey.survey(
        _lines(
            {"weight": "3(Kg)"},
            {"weight": "9.5(Kg)"},
            {"weight": "31(Kg)"},
            {"weight": "미상"},
        )
    )
    assert result["weight_bins"] == {"2~5kg": 1, "8~12kg": 1, "30kg~": 1}
    assert result["weight_unparsed_top"] == {"미상": 1}


def test_breed_variant_groups_catch_spacing_differences():
    result = tag_value_survey.survey(
        _lines(
            {"kindNm": "골든 리트리버"},
            {"kindNm": "골든리트리버"},
            {"kindNm": "골든리트리버"},
            {"kindNm": "푸들"},
        )
    )
    assert result["breed_variant_groups"] == {
        "골든리트리버": {"골든리트리버": 2, "골든 리트리버": 1}
    }


def test_parse_errors_and_blank_lines_do_not_break():
    result = tag_value_survey.survey(["{broken", "", '{"kindNm": "푸들"}'])
    assert result["record_count"] == 1
    assert result["parse_errors"] == 1
