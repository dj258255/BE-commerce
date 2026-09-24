"""가상 사용자의 판단(#292). 화면에 **보인** 상품만 받아 클릭 · 구매할 것을 고른다.

정책은 `decide(persona, page, rnd) -> Decision` 하나만 있으면 된다. 새 정책(다른 LLM, 규칙)을 더할 때는 이 모양을 지킨다.

- `page`: 홈 응답의 행 목록 [{"row": 행 id, "rank": 행 순서, "items": [{"id", "name", "price", "category", "type", "colour", "pos"}]}]
- 결과는 반드시 보인 상품 안에서 고른다. 드라이버가 한 번 더 걸러 밖의 것은 버리고 센다

`rule` 의 숫자(가중치, 확률 곡선, 위치 감쇠)는 **고른 값이다.** 실제 사용자 행동을 흉내 낸다는 주장이 아니다.
"""
import dataclasses
import json
import math
import os
import re
import urllib.request


@dataclasses.dataclass
class Decision:
    clicks: list
    buys: list
    next_page: bool
    note: str = ""


class RulePolicy:
    """보인 상품마다 이 사람 취향과 맞는 정도를 점수로 매기고, 점수와 화면 위치로 확률을 정한다."""

    name = "rule"

    # 점수: 다음 주에 실제로 산 상품이면 크게, 같은 종류 · 색 · 대분류면 조금씩
    W_WANTED, W_TYPE, W_COLOUR, W_CATEGORY = 3.0, 1.2, 0.4, 0.3
    # 클릭 확률 = 위치 감쇠 × sigmoid(점수 − 2.5), 구매 확률(클릭한 것 중) = sigmoid(점수 − 3.0)
    CLICK_BIAS, BUY_BIAS = 2.5, 3.0
    ROW_DECAY, POS_DECAY = 0.25, 0.08       # 아래 행 · 뒷자리일수록 덜 본다
    NEXT_PAGE = 0.5                        # 1쪽에서 아무것도 안 샀을 때 2쪽을 넘길 확률

    def score(self, persona, item):
        s = 0.0
        if item["id"] in set(persona.wanted):
            s += self.W_WANTED
        if item.get("type") in persona.liked_types:
            s += self.W_TYPE
        if item.get("colour") in persona.liked_colours:
            s += self.W_COLOUR
        if item.get("category") in persona.liked_categories:
            s += self.W_CATEGORY
        return s

    def decide(self, persona, page, rnd):
        clicks, buys = [], []
        for row in page:
            seen = 1.0 / (1.0 + self.ROW_DECAY * row["rank"])
            for item in row["items"]:
                attention = seen / (1.0 + self.POS_DECAY * item["pos"])
                s = self.score(persona, item)
                if rnd.random() < attention * sigmoid(s - self.CLICK_BIAS):
                    clicks.append(item["id"])
                    if rnd.random() < sigmoid(s - self.BUY_BIAS):
                        buys.append(item["id"])
        return Decision(clicks, buys, next_page=not buys and rnd.random() < self.NEXT_PAGE)


class OllamaPolicy:
    """로컬 LLM(Ollama)에게 페르소나와 보인 상품을 주고 JSON 으로 고르게 한다.

    VU_OLLAMA_URL(기본 http://localhost:11434), VU_OLLAMA_MODEL(기본 qwen3:8b). 답을 못 읽으면 아무것도 안 하고 note 에 남긴다.

    VU_MAX_CLICKS(기본 3) · VU_MAX_BUYS(기본 1): 한 쪽에서 누를 수 · 살 수의 상한. 프롬프트에 적고 넘친 것은 앞에서부터 자른다.
    상한 없이 돌렸더니 qwen3:8b 가 한 번 방문에 6~22개를 누르고 2~8개를 샀다(#292). 실제 방문과 거리가 멀어 기본으로 막았다.
    상한 값은 고른 값이다 — 실제 사용자 로그가 생기면 그 분포에 맞춘다.
    """

    name = "ollama"

    def __init__(self):
        self.url = os.environ.get("VU_OLLAMA_URL", "http://localhost:11434").rstrip("/") + "/api/chat"
        self.model = os.environ.get("VU_OLLAMA_MODEL", "qwen3:8b")
        self.timeout = float(os.environ.get("VU_OLLAMA_TIMEOUT", "120"))
        self.max_clicks = int(os.environ.get("VU_MAX_CLICKS", "3"))
        self.max_buys = int(os.environ.get("VU_MAX_BUYS", "1"))

    def prompt(self, persona, page):
        lines = []
        for row in page:
            for item in row["items"]:
                lines.append(f'{item["id"]} | {row["title"]} | {item["name"]} | {item.get("type") or "-"} | '
                             f'{item.get("colour") or "-"} | {item["price"]}원')
        return (
            "당신은 온라인 쇼핑몰의 고객입니다. 아래는 당신에 대한 설명과 지금 홈 화면에 보이는 상품입니다.\n"
            f"당신: {persona.describe()}\n\n"
            "보이는 상품(id | 행 | 이름 | 종류 | 색 | 가격):\n" + "\n".join(lines) + "\n\n"
            f"평소처럼 둘러보세요. 관심 가는 상품만 최대 {self.max_clicks}개까지 눌러 보고, 정말 살 것만 최대 {self.max_buys}개 사세요. "
            "아무것도 안 누르거나 안 사도 됩니다.\n"
            "반드시 보이는 상품의 id 만 쓰고, 다음 JSON 한 줄로만 답하세요:\n"
            '{"click": [id, ...], "buy": [id, ...], "next_page": true 또는 false}'
        )

    def decide(self, persona, page, rnd):
        body = {"model": self.model, "stream": False, "format": "json", "think": False,
                "options": {"temperature": 0.7, "seed": rnd.randrange(1 << 30)},
                "messages": [{"role": "user", "content": self.prompt(persona, page)}]}
        try:
            req = urllib.request.Request(self.url, json.dumps(body).encode(), {"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text = json.loads(r.read())["message"]["content"]
            data = json.loads(re.search(r"\{.*\}", text, re.S).group(0))
            ids = lambda xs: [int(x) for x in xs if str(x).strip().lstrip("-").isdigit()]
            clicks = ids(data.get("click", []))[:self.max_clicks]
            buys = [b for b in ids(data.get("buy", [])) if b in clicks][:self.max_buys]   # 누르지 않은 것은 사지 않는다
            return Decision(clicks, buys, bool(data.get("next_page", False)))
        except Exception as e:  # noqa: BLE001  모델 실패는 행동 없음으로 기록한다(추측으로 채우지 않는다)
            return Decision([], [], False, note=f"llm-failed: {type(e).__name__}: {str(e)[:120]}")


class OllamaScorePolicy(OllamaPolicy):
    """LLM 에게 고르게 하지 않고 **보인 상품마다 관심도(0~10)를 매기게** 한 뒤, 클릭 · 구매는 규칙처럼 확률로 뽑는다.

    LLM 이 직접 고르게 하면(`ollama`) qwen3:8b 는 상한을 늘 꽉 채웠다(5명 모두 쪽마다 3개 누르고 1개 사기, #292).
    그러면 무엇을 보여 줘도 행동이 같아 A/B 가 차이를 못 가린다. 점수로 받으면 보인 상품에 따라 행동이 달라진다.
    점수 → 확률의 곡선은 RulePolicy 와 같은 모양이고 값은 고른 값이다.
    """

    name = "ollama-score"
    CLICK_MID, BUY_MID, SLOPE = 7.0, 8.5, 1.2     # 관심도 7 에서 클릭 확률 반(위치 감쇠 전), 8.5 에서 구매 확률 반
    ROW_DECAY, POS_DECAY, NEXT_PAGE = RulePolicy.ROW_DECAY, RulePolicy.POS_DECAY, RulePolicy.NEXT_PAGE

    def prompt(self, persona, page):
        lines = [f'{item["id"]} | {row["title"]} | {item["name"]} | {item.get("type") or "-"} | {item.get("colour") or "-"} | '
                 f'{item["price"]}원' for row in page for item in row["items"]]
        return (
            "당신은 온라인 쇼핑몰의 고객입니다. 아래는 당신에 대한 설명과 지금 홈 화면에 보이는 상품입니다.\n"
            f"당신: {persona.describe()}\n\n"
            "보이는 상품(id | 행 | 이름 | 종류 | 색 | 가격):\n" + "\n".join(lines) + "\n\n"
            "각 상품이 지금 당신에게 얼마나 끌리는지 0(전혀)부터 10(당장 사고 싶음)까지 매기세요. 대부분은 낮은 점수가 자연스럽습니다.\n"
            '다음 JSON 한 줄로만 답하세요: {"scores": {"id": 점수, ...}}'
        )

    def decide(self, persona, page, rnd):
        body = {"model": self.model, "stream": False, "format": "json", "think": False,
                "options": {"temperature": 0.3, "seed": rnd.randrange(1 << 30)},
                "messages": [{"role": "user", "content": self.prompt(persona, page)}]}
        try:
            req = urllib.request.Request(self.url, json.dumps(body).encode(), {"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text = json.loads(r.read())["message"]["content"]
            raw = json.loads(re.search(r"\{.*\}", text, re.S).group(0)).get("scores", {})
            scores = {int(k): float(v) for k, v in raw.items() if str(k).strip().isdigit()}
        except Exception as e:  # noqa: BLE001
            return Decision([], [], False, note=f"llm-failed: {type(e).__name__}: {str(e)[:120]}")
        clicks, buys = [], []
        for row in page:
            seen = 1.0 / (1.0 + self.ROW_DECAY * row["rank"])
            for item in row["items"]:
                s = scores.get(item["id"], 0.0)
                if rnd.random() < seen / (1.0 + self.POS_DECAY * item["pos"]) * sigmoid(self.SLOPE * (s - self.CLICK_MID)):
                    clicks.append(item["id"])
                    if rnd.random() < sigmoid(self.SLOPE * (s - self.BUY_MID)):
                        buys.append(item["id"])
        note = "" if scores else "llm-empty-scores"
        return Decision(clicks, buys, next_page=not buys and rnd.random() < self.NEXT_PAGE, note=note)


def sigmoid(x):
    return 1.0 / (1.0 + math.exp(-x))


POLICIES = {"rule": RulePolicy, "ollama": OllamaPolicy, "ollama-score": OllamaScorePolicy}
