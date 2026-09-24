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
    """

    name = "ollama"

    def __init__(self):
        self.url = os.environ.get("VU_OLLAMA_URL", "http://localhost:11434").rstrip("/") + "/api/chat"
        self.model = os.environ.get("VU_OLLAMA_MODEL", "qwen3:8b")
        self.timeout = float(os.environ.get("VU_OLLAMA_TIMEOUT", "120"))

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
            "평소처럼 둘러보세요. 관심 가는 상품만 눌러 보고, 정말 살 것만 사세요. 아무것도 안 눌러도 됩니다.\n"
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
            return Decision(ids(data.get("click", [])), ids(data.get("buy", [])), bool(data.get("next_page", False)))
        except Exception as e:  # noqa: BLE001  모델 실패는 행동 없음으로 기록한다(추측으로 채우지 않는다)
            return Decision([], [], False, note=f"llm-failed: {type(e).__name__}: {str(e)[:120]}")


def sigmoid(x):
    return 1.0 / (1.0 + math.exp(-x))


POLICIES = {"rule": RulePolicy, "ollama": OllamaPolicy}
