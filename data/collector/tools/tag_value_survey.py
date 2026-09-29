"""공공 API 수집분의 품종·색·체중 원시 값 분포 일회성 조사 (표준 라이브러리만 사용).

속성 태그 통제 어휘(#117, 자카드 셀프조인 입력) 설계 재료를 만든다.
HDFS 수집분(records.jsonl — API 원문 그대로)을 입력으로 고유값·빈도·결측률·표기 변형
후보를 집계하며, 출력은 집계 결과뿐이라 원시 레코드는 저장하지 않는다.

사용법 (서버 2):
    hdfs dfs -cat '/data/shelter/backfill/yyyymm=*/records.jsonl' \
        | python tag_value_survey.py - --out tag-survey.json
    # 로컬 파일도 가능: python tag_value_survey.py a.jsonl b.jsonl --out out.json

기존 수집기 모듈은 사용하지 않는다 — 수집·적재 인터페이스와 무관한 읽기 전용 조사다.
"""

import argparse
import collections
import json
import re
import sys
from datetime import date

# 조사 대상 필드 — docs/erd.md §8 공공 API 필드 매핑에서 태그 후보로 쓰는 것들
SURVEY_FIELDS = ("upKindCd", "upKindNm", "kindCd", "kindNm", "kindFullNm", "colorCd", "weight")

# 색 표기는 자유 텍스트라 복합 표기("흰색&갈색")를 토큰으로 쪼개 본다
COLOR_SPLIT = re.compile(r"[,&+/·\s]+")

# 체중은 "3.2(Kg)" 형태가 대부분 — 앞쪽 숫자만 취한다
WEIGHT_NUM = re.compile(r"^\s*(\d+(?:\.\d+)?)")

# 체중(kg) 히스토그램 경계 — 크기 태그 구간 결정용 (소형<8, 중형 8~20, 대형>20 후보 검토)
WEIGHT_BINS = (2, 5, 8, 12, 16, 20, 25, 30)


def _text(value) -> str:
    """None만 결측으로 본다 — 숫자 0·False가 와도 문자열로 보존해 결측 오집계를 막는다."""
    return "" if value is None else str(value).strip()


def normalize_variant_key(value: str) -> str:
    """공백·구두점 차이만 지운 표기 변형 그룹 키 (음차 차이는 못 잡는다 — 육안 검토용 재료)."""
    return re.sub(r"[\s·\-()\[\]]+", "", value).lower()


def weight_kg(raw: str) -> float | None:
    match = WEIGHT_NUM.match(raw)
    return float(match.group(1)) if match else None


def weight_bin_label(kg: float) -> str:
    lower = 0
    for upper in WEIGHT_BINS:
        if kg < upper:
            return f"{lower}~{upper}kg"
        lower = upper
    return f"{WEIGHT_BINS[-1]}kg~"


def survey(lines) -> dict:
    total = 0
    parse_errors = 0
    missing = collections.Counter()
    freq: dict[str, collections.Counter] = {f: collections.Counter() for f in SURVEY_FIELDS}
    color_tokens = collections.Counter()
    weight_bins = collections.Counter()
    weight_unparsed = collections.Counter()

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
        for field in SURVEY_FIELDS:
            value = _text(record.get(field))
            if not value:
                missing[field] += 1
                continue
            freq[field][value] += 1
        color = _text(record.get("colorCd"))
        if color:
            for token in COLOR_SPLIT.split(color):
                if token:
                    color_tokens[token] += 1
        weight = _text(record.get("weight"))
        if weight:
            kg = weight_kg(weight)
            if kg is None:
                weight_unparsed[weight] += 1
            else:
                weight_bins[weight_bin_label(kg)] += 1

    # 품종 표기 변형 후보 — 정규화 키가 같은데 표기가 2개 이상인 그룹
    breed_variants = {}
    groups: dict[str, list] = collections.defaultdict(list)
    for value, count in freq["kindNm"].items():
        groups[normalize_variant_key(value)].append((value, count))
    for key, forms in sorted(groups.items()):
        if len(forms) > 1:
            breed_variants[key] = dict(sorted(forms, key=lambda x: -x[1]))

    def top(counter: collections.Counter, n: int) -> dict:
        return dict(counter.most_common(n))

    return {
        "generated_on": date.today().isoformat(),
        "record_count": total,
        "parse_errors": parse_errors,
        "missing": {f: missing.get(f, 0) for f in SURVEY_FIELDS},
        "missing_rate": {
            f: round(missing.get(f, 0) / total, 4) if total else None for f in SURVEY_FIELDS
        },
        "unique_counts": {f: len(freq[f]) for f in SURVEY_FIELDS},
        "species": dict(freq["upKindNm"].most_common()),
        "breeds": dict(freq["kindNm"].most_common()),
        "breed_variant_groups": breed_variants,
        "colors_raw_top": top(freq["colorCd"], 300),
        "color_tokens_top": top(color_tokens, 300),
        "weight_bins": dict(sorted(weight_bins.items())),
        "weight_unparsed_top": top(weight_unparsed, 50),
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
    parser.add_argument("--out", required=True, help="집계 JSON 출력 경로")
    args = parser.parse_args()

    result = survey(iter_input_lines(args.inputs))
    with open(args.out, "w", encoding="utf-8") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    print(
        f"records={result['record_count']} parse_errors={result['parse_errors']} "
        f"breeds={result['unique_counts']['kindNm']} colors={result['unique_counts']['colorCd']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
