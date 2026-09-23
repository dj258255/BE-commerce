"""FDS 로지스틱 회귀를 scikit-learn 으로 학습해 자바 학습기와 대조한다.

피처는 여기서 만들지 않는다. 자바가 SequenceFeatures 로 계산해 내보낸 CSV 를 읽는다.
파이썬이 피처를 다시 구현하면 학습이 보는 값과 서빙이 보는 값이 갈리고, 그 어긋남은
점수가 이상해질 때까지 안 보인다.

    ./gradlew -p commerce test --tests '*FraudModelEvalTest.exportsFeatureMatrix'
    tools/fds/.venv/bin/python tools/fds/train.py

산출물은 build/fds/sklearn-model.json 이다. 자바 테스트 agreesWithSklearn 이 그 파일을
읽어 두 학습기의 판정이 같은지 본다.
"""

import json
import pathlib
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression

OUT_DIR = pathlib.Path("build/fds")
# 자바 학습기와 조건을 맞춘다. 자바 쪽에 정규화가 없으므로 여기서도 끈다.
# sklearn 1.8 부터 penalty=None 은 사라질 예정이고 C=inf 가 그 자리를 받는다.
C = np.inf
SOLVER = "lbfgs"
MAX_ITER = 10_000


def load(path):
    if not path.exists():
        sys.exit(f"{path} 가 없다. 먼저 exportsFeatureMatrix 를 돌린다")
    rows = path.read_text().strip().split("\n")
    names = rows[0].split(",")[:-1]
    data = np.array([[float(v) for v in r.split(",")] for r in rows[1:]])
    return names, data[:, :-1], data[:, -1]


def log_loss(w, b, x, y, eps=1e-12):
    p = 1.0 / (1.0 + np.exp(-(x @ w + b)))
    return float(-np.mean(y * np.log(p + eps) + (1 - y) * np.log(1 - p + eps)))


def main():
    names, x, y = load(OUT_DIR / "train.csv")
    _, xh, yh = load(OUT_DIR / "holdout.csv")

    model = LogisticRegression(C=C, solver=SOLVER, max_iter=MAX_ITER)
    model.fit(x, y)
    w, b = model.coef_[0], float(model.intercept_[0])

    print(f"학습 {len(y):,}행 · 피처 {len(names)}개 · 부정 {int(y.sum())}건")
    print(f"수렴 반복 {int(model.n_iter_[0]):,}회 (상한 {MAX_ITER:,})")
    print(f"\n{'피처':<20} {'sklearn':>12}")
    for n, v in zip(names, w):
        print(f"  {n:<18} {v:>12.6f}")
    print(f"  {'bias':<18} {b:>12.6f}")
    print(f"\n학습 손실 {log_loss(w, b, x, y):.6f} · 홀드아웃 손실 {log_loss(w, b, xh, yh):.6f}")

    out = OUT_DIR / "sklearn-model.json"
    out.write_text(json.dumps({
        "features": names,
        "weights": [float(v) for v in w],
        "bias": b,
        "trainLogLoss": log_loss(w, b, x, y),
        "holdoutLogLoss": log_loss(w, b, xh, yh),
        "sklearn": __import__("sklearn").__version__,
        "penalty": "none (C=inf)",
        "solver": SOLVER,
    }, indent=2, ensure_ascii=False))
    print(f"\n→ {out}")


if __name__ == "__main__":
    main()
