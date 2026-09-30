"""D3(#437) · D4(#448) · D5(#450) 페이지 대 페이지 — 상품은 같게, 줄 구성만 다르게 페이지를 만든다.

STATUS.md 의 "D3" · "D4" · "D5" 절에 **측정 전에** 적은 변형 B · G-row · G-full ·
H · G-row-thin · H-thin 을 그대로 구현한다. 공통 조건은 상품(순위 모델 R 의 점수
상위 200개) · 페이지(6행 × 8개, 한 상품은 한 번만) · 행(H&M 섹션 행 + 다시 사기
행)이다.

- ``B`` 규칙 줄 구성(기존 방식): 점수 상위 200개를 행별로 묶고, 행마다 점수 상위
  8개의 점수 합이 큰 행부터 6개, 행 안은 점수 순 8개
- ``G-row``: GenPage 가 행 토큰을 생성한다. 허용 행은 상위 200개에 상품이 8개
  이상인 행이고, 행 안 상품은 그 행의 점수 순 상품으로 채운다
- ``G-full``: 허용 상품 = 상위 200개, 행 토큰 · 상품 모두 GenPage 가 생성한다.
  다시 사기 행이 나오면 최근 산 순서로 채운다
- ``H(λ)``: G-row 와 같되, 행을 고를 때 GenPage 의 행 로그 확률에 λ × z 를 더한다.
  z 는 그 고객의 허용 행들 사이에서 행 점수(B 와 같은 값)를 표준화한 값이다
- ``G-row-thin``(= G-row'): G-row 와 같되, 허용 행을 상위 200개에 상품이 1개 이상인
  행으로 넓히고 행 안을 그 행의 점수 순 최대 8개로 못박는다(모델이 채우지 않는다).
  그래서 8개 미만인 행도 B 처럼 짧은 행으로 나온다
- ``H-thin``(= H'): G-row-thin 과 같되, 행 로그 확률에 λ × z 를 더한다(D4 와 같은 z)

출력은 :mod:`genpage2.evaluate` 조각과 같은 모양(``mode`` · ``ckpt`` · ``args`` ·
``device`` · ``shard`` · ``pages[].rows[].items``)이라 :mod:`genpage2.merge_eval` 과
:mod:`genpage2.paired_compare` 가 그대로 읽는다.
"""
from __future__ import annotations

import argparse
import gzip
import json
import time
from pathlib import Path
from typing import Any

from . import config
from .config import ITEMS_PER_ROW, MAX_ROWS
from .decode import GeneratedRow
from .evaluate import (_as_articles, _context_at, _load_decoder, _load_examples,
                       _recent_repeat_items, load_eval_assets, parse_shard, set_torch_threads,
                       shard_bounds)

# STATUS D3 · D4 의 공통 조건. 행 = 아마존과 같은 섹션 행 + 다시 사기 행(이력 상품).
TOP = 200
ROWS = MAX_ROWS
ITEMS = ITEMS_PER_ROW
VARIANTS = ("B", "G-row", "G-full", "H", "G-row-thin", "H-thin")
# 행 후보 자격의 최소 상품 수. D3 · D4 는 8개 미만 행을 막았지만 D5 는 B 처럼
# 상품이 1개 이상이면 받는다.
MIN_ROW_ITEMS = 1


def _read_json(path: Path) -> Any:
    if str(path).endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding="utf-8"))


def read_scores(path: str | Path) -> dict[str, list[tuple[str, float]]]:
    """랭커의 ``scores.json.gz``(``[{customer_id, scores}]``)를 읽는다."""
    records = _read_json(Path(path))
    return {str(record["customer_id"]): [(str(article), float(score)) for article, score in record["scores"]]
            for record in records}


def _row_groups(scored: list[tuple[str, float]], history: set[str], vocab: Any, *,
                top: int = TOP) -> dict[int, list[tuple[float, str]]]:
    """점수 상위 ``top`` 개를 행별로 묶는다(행 안은 점수 내림차순).

    이력에 있는 상품은 다시 사기 행(``ROW_REPEAT``)으로, 나머지는 그 상품의 섹션
    행으로 간다. 그래서 한 상품은 정확히 한 행에만 들어간다.
    """
    repeat = vocab.id("ROW_REPEAT")
    groups: dict[int, list[tuple[float, str]]] = {}
    for article, score in scored[:top]:
        if vocab.item(article) is None:
            continue
        if article in history:
            row = repeat
        else:
            try:
                row = vocab.row_of(article)
            except KeyError:
                continue
        groups.setdefault(row, []).append((score, article))
    for values in groups.values():
        values.sort(key=lambda pair: (-pair[0], pair[1]))
    return groups


def row_score_sums(groups: dict[int, list[tuple[float, str]]], *, items: int = ITEMS) -> dict[int, float]:
    """행 점수 = 그 행의 R 점수 상위 ``items`` 개의 합(B 와 H 가 함께 쓴다)."""
    return {row: sum(score for score, _ in values[:items]) for row, values in groups.items()}


def top_items(scored: list[tuple[str, float]], vocab: Any, *, top: int = TOP) -> set[str]:
    """점수 상위 ``top`` 개 중 어휘에 있는 상품 집합(허용 상품 · G-full 과 같다)."""
    return {article for article, _ in scored[:top] if vocab.item(article) is not None}


def compose_b(scored: list[tuple[str, float]], history: list[str], vocab: Any, *,
              rows: int = ROWS, items: int = ITEMS) -> list[GeneratedRow]:
    """B 규칙 줄 구성. 행마다 점수 상위 ``items`` 개의 점수 합이 큰 행부터 ``rows`` 개."""
    groups = _row_groups(scored, set(history), vocab)
    sums = row_score_sums(groups, items=items)
    entries: list[tuple[float, int, list[str]]] = [
        (sums[row], row, [article for _, article in values[:items]])
        for row, values in groups.items()
    ]
    entries.sort(key=lambda entry: (-entry[0], entry[1]))
    return [GeneratedRow(row, items_list) for _, row, items_list in entries[:rows]]


def _group_inputs(groups: dict[int, list[tuple[float, str]]], *,
                  items: int = ITEMS, min_items: int | None = None
                  ) -> tuple[dict[int, list[str]], set[int]]:
    """``row_items`` · ``allowed_rows`` 를 행 묶음에서 만든다(G-row · H 공통).

    허용 행은 그 행의 상위 ``top`` 개 상품이 ``min_items`` 개 이상인 행이다.
    ``min_items`` 가 ``None`` 이면 ``items``(8)개 이상이라 D3 · D4 와 같다.
    """
    threshold = items if min_items is None else min_items
    allowed_rows = {row for row, values in groups.items() if len(values) >= threshold}
    row_items = {row: [article for _, article in groups[row][:items]] for row in allowed_rows}
    return row_items, allowed_rows


def g_row_inputs(scored: list[tuple[str, float]], history: list[str], vocab: Any, *,
                 items: int = ITEMS, top: int = TOP
                 ) -> tuple[dict[int, list[str]], set[int]]:
    """G-row 의 ``row_items`` · ``allowed_rows`` 를 만든다.

    허용 행은 상위 ``top`` 개에 상품이 ``items`` 개 이상인 행이고, ``row_items``
    는 그 행의 점수 순 상품이다. 행 안 상품이 점수 순이 되는 이유가 이것이다.
    """
    return _group_inputs(_row_groups(scored, set(history), vocab, top=top), items=items)


def _standardize(row_lambda: float, sums: dict[int, float], allowed_rows: set[int]) -> dict[int, float]:
    """허용 행 안에서 행 점수를 평균 0 · 표준편차 1 로 표준화하고 λ 를 곱한다.

    허용 행이 하나뿐이거나 표준편차가 0 이면 모든 z 가 0 이라 bias 도 0 이다
    (그 고객의 H 페이지는 G-row 와 같아진다).
    """
    values = [sums[row] for row in allowed_rows]
    if len(values) < 2:
        return {row: 0.0 for row in allowed_rows}
    mean = sum(values) / len(values)
    std = (sum((value - mean) ** 2 for value in values) / len(values)) ** 0.5
    if std == 0:
        return {row: 0.0 for row in allowed_rows}
    return {row: row_lambda * (sums[row] - mean) / std for row in allowed_rows}


def h_inputs(scored: list[tuple[str, float]], history: list[str], vocab: Any, row_lambda: float, *,
             items: int = ITEMS, top: int = TOP
             ) -> tuple[dict[int, list[str]], set[int], dict[int, float]]:
    """H(λ) 의 ``row_items`` · ``allowed_rows`` · ``row_bias`` 를 만든다.

    허용 행과 ``row_items`` 는 G-row 와 같다. ``row_bias`` 는 그 고객의 허용 행
    안에서 행 점수(상위 ``items`` 개의 합, B 와 같은 값)를 표준화한 z 에 λ 를
    곱한 값이다. 디코더는 행 로그 확률에 이 bias 를 더해 고른다.
    """
    groups = _row_groups(scored, set(history), vocab, top=top)
    row_items, allowed_rows = _group_inputs(groups, items=items)
    row_bias = _standardize(row_lambda, row_score_sums(groups, items=items), allowed_rows)
    return row_items, allowed_rows, row_bias


def _thin_inputs(groups: dict[int, list[tuple[float, str]]], scored: list[tuple[str, float]],
                 vocab: Any, *, items: int = ITEMS, top: int = TOP
                 ) -> tuple[set[str], dict[int, list[str]], set[int]]:
    """G-row-thin · H-thin 의 ``allowed_items`` · ``row_items`` · ``allowed_rows``.

    허용 행은 상위 ``top`` 개에 상품이 1개 이상인 행이라 B 가 고를 수 있는 행과
    같은 집합이다. ``row_items`` 는 그 행의 점수 순 상위 ``items`` 개(=B 의 행 안)다.
    ``allowed_items`` 는 상위 ``top`` 개(허용 상품)다. 다만 이 마스크만으로는 행을
    못박지 못한다. 이력 상품은 자기 섹션 행과 다시 사기 행에 모두 속하므로, 상위
    ``top`` 개에 그 상품이 있으면 섹션 행이 모델로 채워질 때 다시 사기 행 몫을
    가져갈 수 있다. 디코더의 ``row_items_only`` 가 그 채우기를 막아 행을 ``row_items``
    앞에서 끝낸다.
    """
    row_items, allowed_rows = _group_inputs(groups, items=items, min_items=MIN_ROW_ITEMS)
    allowed_items = top_items(scored, vocab, top=top)
    return allowed_items, row_items, allowed_rows


def g_row_thin_inputs(scored: list[tuple[str, float]], history: list[str], vocab: Any, *,
                      items: int = ITEMS, top: int = TOP
                      ) -> tuple[set[str], dict[int, list[str]], set[int]]:
    """G-row-thin 의 ``allowed_items`` · ``row_items`` · ``allowed_rows``(D5 G-row')."""
    groups = _row_groups(scored, set(history), vocab, top=top)
    return _thin_inputs(groups, scored, vocab, items=items, top=top)


def h_thin_inputs(scored: list[tuple[str, float]], history: list[str], vocab: Any, row_lambda: float, *,
                  items: int = ITEMS, top: int = TOP
                  ) -> tuple[set[str], dict[int, list[str]], set[int], dict[int, float]]:
    """H-thin 의 ``allowed_items`` · ``row_items`` · ``allowed_rows`` · ``row_bias``.

    허용 행 · ``row_items`` · ``allowed_items`` 는 G-row-thin 과 같고, ``row_bias``
    는 그 고객의 허용 행들 안에서 행 점수(상위 ``items`` 개의 합, B 와 같은 값)를
    표준화한 z 에 λ 를 곱한 값이다. D4 의 H 와 같은 z 이되 허용 행이 8개 미만 행까지
    넓어진 점만 다르다. D4 H 는 ``row_items`` 만 돌려주므로 D5 는 별도 함수로 둔다.
    """
    groups = _row_groups(scored, set(history), vocab, top=top)
    allowed_items, row_items, allowed_rows = _thin_inputs(groups, scored, vocab, items=items, top=top)
    row_bias = _standardize(row_lambda, row_score_sums(groups, items=items), allowed_rows)
    return allowed_items, row_items, allowed_rows, row_bias


def g_full_inputs(scored: list[tuple[str, float]], history: list[str], vocab: Any, *,
                  top: int = TOP) -> tuple[set[str], dict[int, list[str]]]:
    """G-full 의 ``allowed_items`` · ``row_items`` 를 만든다.

    허용 상품은 상위 ``top`` 개다. 다시 사기 행이 나오면 그 행을 최근 산 순서로
    채우도록 ``ROW_REPEAT`` 의 ``row_items`` 에 **이력 전체**를 최근 순서로
    넘긴다(중복 없이, 어휘 상품만). 앞의 상품이 허용 밖이면 디코더가 건너뛰고
    뒤의 최근 상품이 그 자리를 채우며, 8개가 차면 멈춘다. 그래서 최근 8개 안에
    허용 밖 상품이 있어도 9번째 이후 최근 상품이 들어간다.
    """
    allowed_items = top_items(scored, vocab, top=top)
    row_items = {vocab.id("ROW_REPEAT"): _recent_repeat_items(history, vocab, len(history))}
    return allowed_items, row_items


def build_pages(variant: str, meta: Any, archive: Any, scores: dict[str, list[tuple[str, float]]], *,
                vocab: Any, decoder: Any = None, batch: int = 256, row_lambda: float = 0.0
                ) -> tuple[dict[str, list[GeneratedRow]], dict[str, int]]:
    """고객마다 변형 페이지를 만든다. ``meta`` 순서대로 돌려준다."""
    pages: dict[str, list[GeneratedRow]] = {}
    violations: dict[str, int] = {}
    queued = list(meta.iterrows())
    for begin in range(0, len(queued), batch):
        part = queued[begin:begin + batch]
        if variant == "B":
            for _, row in part:
                customer = str(row.customer_id)
                pages[customer] = compose_b(scores.get(customer, []), _as_articles(row.history), vocab)
                violations[customer] = 0
            continue
        examples = []
        for index, row in part:
            customer = str(row.customer_id)
            ctx_tokens, ctx_content = _context_at(archive, int(index))
            history = _as_articles(row.history)
            scored = scores.get(customer, [])
            if variant in ("G-row", "G-row-thin", "H", "H-thin"):
                allowed_items = None
                row_bias = None
                if variant in ("H", "H-thin"):
                    if variant == "H":
                        row_items, allowed_rows, row_bias = h_inputs(scored, history, vocab, row_lambda)
                    else:
                        allowed_items, row_items, allowed_rows, row_bias = h_thin_inputs(
                            scored, history, vocab, row_lambda)
                elif variant == "G-row":
                    row_items, allowed_rows = g_row_inputs(scored, history, vocab)
                else:
                    allowed_items, row_items, allowed_rows = g_row_thin_inputs(scored, history, vocab)
                example = {"ctx_tokens": ctx_tokens, "ctx_content": ctx_content,
                           "history_articles": history, "row_items": row_items,
                           "allowed_rows": allowed_rows, "row_bias": row_bias}
                if allowed_items is not None:
                    example["allowed_items"] = allowed_items
            else:
                allowed_items, row_items = g_full_inputs(scored, history, vocab)
                example = {"ctx_tokens": ctx_tokens, "ctx_content": ctx_content,
                           "history_articles": history, "allowed_items": allowed_items,
                           "row_items": row_items}
            examples.append(example)
        decode_kwargs: dict[str, Any] = {"n_rows": ROWS, "items_per_row": ITEMS, "prefix": 2}
        if variant in ("G-row-thin", "H-thin"):
            # 8개 미만 행도 허용하고, 행은 row_items 앞에서 끝낸다(모델이 채우지 않는다).
            decode_kwargs["min_row_items"] = MIN_ROW_ITEMS
            decode_kwargs["row_items_only"] = True
        decoded = decoder.generate_batch(examples, **decode_kwargs)
        for (_, row), (page, bad) in zip(part, decoded):
            customer = str(row.customer_id)
            pages[customer], violations[customer] = page, bad
    return pages, violations


def _require_customers(scores: dict[str, Any], meta: Any) -> None:
    missing = {str(customer) for customer in meta["customer_id"]} - set(scores)
    if missing:
        raise ValueError(f"scores.json.gz 에 평가 고객이 없습니다 (빠짐 {len(missing)})")


def _report_args(args: argparse.Namespace) -> dict[str, Any]:
    report_args = dict(vars(args))
    # 조각마다 다를 수밖에 없는 실행 인자는 합칠 때 비교하지 않는다(evaluate 와 같다).
    for key in ("shard", "out", "threads"):
        report_args.pop(key, None)
    # λ 는 H 의 옵션이라 H 가 아니면 종전 조각 모양을 유지한다.
    if args.variant not in ("H", "H-thin"):
        report_args.pop("row_lambda", None)
    return report_args


def run(args: argparse.Namespace) -> dict[str, Any]:
    began = time.perf_counter()
    if args.variant not in VARIANTS:
        raise ValueError(f"--variant 는 {sorted(VARIANTS)} 중 하나: {args.variant}")
    row_lambda = getattr(args, "row_lambda", None)
    if args.variant in ("H", "H-thin") and row_lambda is None:
        raise ValueError("H · H-thin 변형에는 --row-lambda 가 필요합니다")
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    meta, archive = _load_examples(base, args.mode, args.limit)
    scores = read_scores(args.scores)
    _require_customers(scores, meta)
    shard = parse_shard(getattr(args, "shard", None)) or (1, 1)
    index, total = shard
    start, stop = shard_bounds(len(meta), index, total)
    shard_meta = meta.iloc[start:stop]
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    set_torch_threads(getattr(args, "threads", None))

    decoder: Any = None
    device = "rule"
    if args.variant == "B":
        vocab, _content, _content_rows = load_eval_assets(mode_dir)
    else:
        if not args.ckpt:
            raise ValueError("G 변형에는 --ckpt 가 필요합니다")
        decoder, vocab, _content, _content_rows, device = _load_decoder(mode_dir, Path(args.ckpt),
                                                                        args.device)

    generated_at = time.perf_counter()
    pages, violations = build_pages(args.variant, shard_meta, archive, scores, vocab=vocab,
                                    decoder=decoder, batch=args.batch, row_lambda=row_lambda or 0.0)
    generation_seconds = time.perf_counter() - generated_at

    options: dict[str, Any] = {"compose": args.variant}
    if args.variant in ("H", "H-thin"):
        options["row_lambda"] = row_lambda
    report: dict[str, Any] = {
        "mode": args.mode,
        "ckpt": str(args.ckpt) if args.ckpt else None,
        "args": _report_args(args),
        "device": device,
        "shard": {"index": index, "total": total, "customers": int(len(shard_meta))},
        "elapsed_seconds": time.perf_counter() - began,
        "generation_seconds": generation_seconds,
        "options": options,
        "pages": [
            {"customer_id": str(row.customer_id),
             "rows": [{"row_token": int(generated.row_token), "items": [str(a) for a in generated.items]}
                      for generated in pages.get(str(row.customer_id), [])],
             "violations": int(violations.get(str(row.customer_id), 0))}
            for row in shard_meta.itertuples(index=False)
        ],
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--scores", required=True, help="랭커가 쓴 scores.json.gz")
    parser.add_argument("--variant", required=True, choices=VARIANTS)
    parser.add_argument("--ckpt", help="G · H 변형의 체크포인트")
    parser.add_argument("--row-lambda", type=float,
                        help="H · H-thin 변형에서 행 로그 확률에 더하는 λ×z 의 λ")
    parser.add_argument("--shard", metavar="K/N")
    parser.add_argument("--out", required=True, help="결과 JSON 경로")
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--data-dir")
    parser.add_argument("--threads", type=int)
    args = parser.parse_args(argv)
    report = run(args)
    destination = Path(args.out)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float),
                           encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
