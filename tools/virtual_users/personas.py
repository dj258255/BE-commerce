"""가상 사용자 페르소나(#292). H&M 고객 한 명이 가상 사용자 한 명이다.

- 과거 구매(홀드아웃 주 이전)는 그 회원의 결제 완료 주문으로 심는다. 실험군(`rec-history`)은 이 구매 이력을 모델에 넣는다
- 취향은 그 고객이 **홀드아웃 주(2020-09-16~22)에 실제로 산 상품**에서 뽑는다. 무엇을 보여 줘야 이 사람이 반응하는지의 정답이
  데이터에 있는 셈이라, 추천이 그 방향으로 가면 가상 사용자의 반응이 늘어난다

취향은 앱에 보이는 속성으로만 적는다(상품 id · 종류 · 색 · 대분류). 가상 사용자가 화면에서 알 수 없는 것을 판단에 쓰지 않게 하려는 것이다.
"""
import collections
import dataclasses
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import genpage_purchase_eval as gpe  # noqa: E402  같은 표본(seed 7) · 같은 DB 접속

HISTORY_LIMIT = gpe.HISTORY_LIMIT


@dataclasses.dataclass
class Persona:
    i: int
    customer: str
    history: list          # 과거 구매 상품 id(오래된 것부터). 주문으로 심는다
    days: list             # history 와 같은 길이의 구매일
    wanted: list           # 홀드아웃 주에 산 상품 id — 이 사람이 실제로 원한 것
    liked_types: dict      # 종류 → 횟수(wanted 에서)
    liked_colours: dict
    liked_categories: dict
    recent_names: list     # 최근 산 상품 이름 몇 개 — LLM 에게 사람을 설명할 때 쓴다

    def describe(self):
        """LLM 에게 줄 한 문단. 앱에 보이는 말로만 쓴다."""
        top = lambda d: ", ".join(k for k, _ in collections.Counter(d).most_common(3)) or "없음"
        return (f"최근에 산 것: {', '.join(self.recent_names[:6]) or '없음'}. "
                f"좋아하는 종류: {top(self.liked_types)}. 좋아하는 색: {top(self.liked_colours)}. "
                f"자주 보는 대분류: {top(self.liked_categories)}.")


def attributes(product_ids):
    """상품 id → (이름, 종류, 색, 대분류). 앱 DB 에서 읽는다(화면에 보이는 속성과 같은 원천)."""
    ids = sorted({int(p) for p in product_ids})
    out = {}
    if not ids:
        return out
    conn = gpe.db()
    with conn.cursor() as c:
        for k in range(0, len(ids), 1000):
            chunk = ids[k:k + 1000]
            c.execute("SELECT product_id, name, product_type, colour_name, category_code FROM products "
                      f"WHERE product_id IN ({','.join(['%s'] * len(chunk))})", chunk)
            for pid, name, ptype, colour, cat in c.fetchall():
                out[pid] = (name, ptype, colour, cat)
    conn.close()
    return out


def build(out, n):
    """#254 의 홀드아웃 표본(seed 7)에서 앞 n 명을 페르소나로 만든다."""
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if not (out / "sample.json").exists():
        gpe.prepare(str(out))
    sample = json.loads((out / "sample.json").read_text())[:int(n)]
    attrs = attributes([p for c in sample for p in c["truth"] + c["history"][-10:]])
    personas = []
    for c in sample:
        types, colours, cats = collections.Counter(), collections.Counter(), collections.Counter()
        for p in c["truth"]:
            if p in attrs:
                _, t, col, cat = attrs[p]
                types[t] += 1
                colours[col] += 1
                cats[cat] += 1
        recent = [attrs[p][0] for p in reversed(c["history"][-10:]) if p in attrs]
        personas.append(Persona(c["i"], c["customer"], c["history"], c["days"], c["truth"],
                                dict(types), dict(colours), dict(cats), recent))
    (out / "personas.json").write_text(json.dumps([dataclasses.asdict(p) for p in personas], ensure_ascii=False))
    print(f"페르소나 {len(personas)}명 · 취향 속성이 있는 사람 {sum(1 for p in personas if p.liked_types)}명")
    return personas


def load(out):
    return [Persona(**d) for d in json.loads((pathlib.Path(out) / "personas.json").read_text())]
