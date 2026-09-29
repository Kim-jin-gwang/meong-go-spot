"""통제 어휘(data/tags/vocabulary.json) 정규화 매핑의 실데이터 커버리지 측정 (표준 라이브러리만 사용).

vocabulary.json의 normalization_rules를 그대로 구현해 공공 수집분(records.jsonl)에
적용했을 때 품종·색·크기 태그가 얼마나 생성되는지, 무엇이 매핑에 실패하는지 집계한다.
어휘 개정 때마다 다시 돌려 커버리지 회귀를 확인하는 용도다. 출력은 집계뿐이다.

사용법 (서버 2):
    hdfs dfs -cat '/data/shelter/backfill/yyyymm=202[456]*/records.jsonl' \
        | python check_vocab_coverage.py - --vocab vocabulary.json --out coverage.json
"""

import argparse
import collections
import json
import re
import sys

WRAPPER = re.compile(r"기타\s*\(|\)")  # '기타(흰색)'·'기타 (흰색)' 래퍼 — 규칙 1
SPLIT = re.compile(r"[,&+/·.\s]+")  # 복합 표기 구분자 — 규칙 2
STRIP = re.compile(r"[\s·\-()\[\]]+")  # 토큰 정규화 — 규칙 3
WEIGHT_NUM = re.compile(r"^\s*(\d+(?:\.\d+)?)")


def norm(token: str) -> str:
    return STRIP.sub("", token).lower()


def _text(value) -> str:
    """None만 결측으로 본다 — 숫자 0·False가 와도 문자열로 보존해 결측 오집계를 막는다."""
    return "" if value is None else str(value).strip()


def build_lookups(vocab: dict) -> tuple[dict, dict]:
    """(색 토큰 → 코드 목록, 품종 정규화명 → 코드) 조회 테이블을 만든다."""
    color_map: dict[str, list[str]] = {}
    color = vocab["attributes"]["color"]
    for value in color["values"]:
        for form in [value["label"], *value.get("aliases", [])]:
            color_map[norm(form)] = [value["code"]]
    for compound, codes in color.get("compound_aliases", {}).items():
        color_map[norm(compound)] = list(codes)
    for token in color.get("ignore_tokens", []):  # 수식어 — 색 정보 없음, 실패로 세지 않는다
        color_map[norm(token)] = []

    breed_map: dict[str, str] = {}
    for value in vocab["attributes"]["breed"]["values"]:
        for form in [value["label"], *value.get("aliases", [])]:
            breed_map[norm(form)] = value["code"]
    return color_map, breed_map


def color_codes(raw: str, color_map: dict) -> tuple[set[str], list[str]]:
    """원시 색 문자열 → (canonical 코드 집합, 매핑 실패 토큰 목록)."""
    codes: set[str] = set()
    unmapped: list[str] = []
    for token in SPLIT.split(WRAPPER.sub(" ", raw)):
        key = norm(token)
        if not key:
            continue
        if key in color_map:
            codes.update(color_map[key])
        else:
            unmapped.append(key)
    return codes, unmapped


def size_code(raw: str, thresholds=(8.0, 20.0)) -> str | None:
    match = WEIGHT_NUM.match(raw)
    if not match:
        return None
    kg = float(match.group(1))
    if kg <= 0:  # '0(Kg)'는 미측정 관례 — 소형으로 오분류하지 않고 태그 생략
        return None
    if kg < thresholds[0]:
        return "small"
    if kg < thresholds[1]:
        return "medium"
    return "large"


def check(lines, vocab: dict) -> dict:
    color_map, breed_map = build_lookups(vocab)
    total = 0
    parse_errors = 0
    breed_hit = 0
    breed_unmapped = collections.Counter()
    color_present = 0
    color_full = 0  # 모든 토큰 매핑
    color_partial = 0  # 일부 토큰만 매핑 (태그는 생성됨)
    color_zero = 0  # 코드 0개 (태그 생략)
    token_unmapped = collections.Counter()
    code_freq = collections.Counter()
    size_hit = 0
    size_missing = 0

    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError:
            parse_errors += 1
            continue
        total += 1

        kind = _text(record.get("kindNm"))
        if kind and norm(kind) in breed_map:
            breed_hit += 1
        elif kind:
            breed_unmapped[kind] += 1

        raw_color = _text(record.get("colorCd"))
        if raw_color:
            color_present += 1
            codes, unmapped = color_codes(raw_color, color_map)
            for token in unmapped:
                token_unmapped[token] += 1
            for code in codes:
                code_freq[code] += 1
            if codes and not unmapped:
                color_full += 1
            elif codes:
                color_partial += 1
            else:
                color_zero += 1

        raw_weight = _text(record.get("weight"))
        if raw_weight and size_code(raw_weight):
            size_hit += 1
        else:
            size_missing += 1

    return {
        "record_count": total,
        "parse_errors": parse_errors,
        "breed": {
            "mapped": breed_hit,
            "mapped_rate": round(breed_hit / total, 4) if total else None,
            "unmapped_top": dict(breed_unmapped.most_common(30)),
        },
        "color": {
            "present": color_present,
            "records_all_tokens_mapped": color_full,
            "records_partially_mapped": color_partial,
            "records_no_code": color_zero,
            "record_tag_rate": round((color_full + color_partial) / color_present, 4)
            if color_present
            else None,
            "code_freq": dict(code_freq.most_common()),
            "unmapped_tokens_top": dict(token_unmapped.most_common(100)),
        },
        "size": {
            "mapped": size_hit,
            "mapped_rate": round(size_hit / total, 4) if total else None,
        },
    }


def iter_input_lines(paths: list[str]):
    for path in paths:
        if path == "-":
            yield from sys.stdin
        else:
            with open(path, encoding="utf-8") as file:
                yield from file


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", nargs="+", help="records.jsonl 경로 목록 또는 '-'(stdin)")
    parser.add_argument("--vocab", required=True, help="vocabulary.json 경로")
    parser.add_argument("--out", required=True, help="집계 JSON 출력 경로")
    args = parser.parse_args()

    with open(args.vocab, encoding="utf-8") as file:
        vocab = json.load(file)
    result = check(iter_input_lines(args.inputs), vocab)
    with open(args.out, "w", encoding="utf-8") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    print(
        f"records={result['record_count']} breed_rate={result['breed']['mapped_rate']} "
        f"color_tag_rate={result['color']['record_tag_rate']} size_rate={result['size']['mapped_rate']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
