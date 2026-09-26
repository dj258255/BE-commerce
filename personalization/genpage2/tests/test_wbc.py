from __future__ import annotations

import argparse
import json
import math
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from genpage2.content import save_content
from genpage2.decode import GeneratedRow, PageDecoder
from genpage2.evaluate import _load_decoder
from genpage2.model import GenPageV2, ModelConfig, save_checkpoint
from genpage2.vocab import AGE_BUCKETS, CLUB_VALUES, NEWS_VALUES, SPECIAL, Vocab
from genpage2.wbc import (
    _generate_exposures,
    _wbc_objective,
    _wbc_terms,
    _weighted_sum,
    chosen_logits,
    evaluate_wbc,
    exposure_from_rows,
    make_wbc_batch,
    run,
    train,
    wbc_loss,
    weighted_auc,
)


ARTICLES = [f"{index:010d}" for index in range(1, 6)]
MANY_ARTICLES = [f"{index:010d}" for index in range(1, 31)]
WIDE_ROWS = 3
WIDE_PER_ROW = len(MANY_ARTICLES) // WIDE_ROWS


def real_vocab() -> Vocab:
    profile = ([f"AGE_{value}" for value in AGE_BUCKETS]
               + [f"CLUB_{value}" for value in CLUB_VALUES]
               + [f"NEWS_{value}" for value in NEWS_VALUES]
               + ["FN_1", "FN_NA", "ACTIVE_1", "ACTIVE_NA"])
    request = [f"DOW_{value}" for value in range(7)] + [f"MONTH_{value}" for value in range(1, 13)]
    actions = ["ACT_STORE", "ACT_ONLINE", "ACT_VIEW", "ACT_CLICK"]
    ago = ["AGO_0-3", "AGO_4-7", "AGO_8-14", "AGO_15-30", "AGO_31-60", "AGO_61-120", "AGO_121-365", "AGO_366+"]
    prices = [f"PRICE_{value}" for value in range(8)]
    rows = ["ROW_REPEAT", "ROW_S1"]
    tokens = SPECIAL + profile + request + actions + ago + prices + rows + [f"ITEM_{article}" for article in ARTICLES]
    row_token = tokens.index("ROW_S1")
    article_rows = {article: row_token for article in ARTICLES}
    return Vocab(tokens, article_rows)


def wide_vocab() -> Vocab:
    """상품 30개를 같은 크기의 세 행에 나눠 담아 노출 길이가 행마다 같게 만든다."""
    profile = ([f"AGE_{value}" for value in AGE_BUCKETS]
               + [f"CLUB_{value}" for value in CLUB_VALUES]
               + [f"NEWS_{value}" for value in NEWS_VALUES]
               + ["FN_1", "FN_NA", "ACTIVE_1", "ACTIVE_NA"])
    request = [f"DOW_{value}" for value in range(7)] + [f"MONTH_{value}" for value in range(1, 13)]
    actions = ["ACT_STORE", "ACT_ONLINE", "ACT_VIEW", "ACT_CLICK"]
    ago = ["AGO_0-3", "AGO_4-7", "AGO_8-14", "AGO_15-30", "AGO_31-60", "AGO_61-120", "AGO_121-365", "AGO_366+"]
    prices = [f"PRICE_{value}" for value in range(8)]
    rows = ["ROW_REPEAT"] + [f"ROW_S{index}" for index in range(1, WIDE_ROWS + 1)]
    tokens = SPECIAL + profile + request + actions + ago + prices + rows + [f"ITEM_{article}" for article in MANY_ARTICLES]
    article_rows = {article: tokens.index(f"ROW_S{index // WIDE_PER_ROW + 1}")
                    for index, article in enumerate(MANY_ARTICLES)}
    return Vocab(tokens, article_rows)


def small_model(vocab: Vocab, *, seed: int | None = None) -> GenPageV2:
    if seed is not None:
        torch.manual_seed(seed)
    cfg = ModelConfig(vocab_size=len(vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                      dropout=0.0, maxlen=64, content_dim=384)
    return GenPageV2(cfg, tokens=vocab.tokens)


class ExampleData:
    """``NpzExamples`` 처럼 (ctx, content, page) 를 인덱스로 내주는 최소 대역."""

    def __init__(self, examples) -> None:
        self.examples = list(examples)

    def __len__(self) -> int:
        return len(self.examples)

    def __getitem__(self, index: int):
        return self.examples[index]


def item_logits(model: GenPageV2, batch: dict, tokens: list[int]) -> float:
    """배치의 상품 위치에서 주어진 상품 토큰들의 평균 로짓."""
    model.eval()
    ids = torch.tensor(tokens, dtype=torch.long)
    with torch.no_grad():
        hidden = model(batch["tokens"], batch["content"])
        selected = hidden[:, :-1][batch["mask"]]
        positions = selected[batch["is_item"][batch["mask"]]]
        repeated = positions.repeat_interleave(len(ids), dim=0)
        tiled = ids.repeat(positions.shape[0])
        return float(chosen_logits(model, repeated, tiled).mean())


def legacy_wbc_curve(model: GenPageV2, exposures, *, vocab: Vocab, level: str, maxlen: int, device: str,
                     epochs: int, batch: int, lr: float, warmup: int, seed: int) -> list[dict]:
    """개조 전 ``train`` 의 손실 곡선을 그대로 재현한다(회귀 기준)."""
    torch.manual_seed(seed)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)
    steps_per_epoch = max(1, -(-len(exposures) // batch))
    planned = max(1, steps_per_epoch * epochs)

    def factor(step: int) -> float:
        if step < warmup:
            return (step + 1) / max(1, warmup)
        progress = min(1.0, (step - warmup) / max(1, planned - warmup))
        return 0.5 * (1 + math.cos(math.pi * progress))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, factor)
    generator = torch.Generator().manual_seed(seed)
    curve: list[dict] = []
    step = 0
    for _ in range(epochs):
        order = torch.randperm(len(exposures), generator=generator).tolist()
        for begin in range(0, len(order), batch):
            part = [exposures[index] for index in order[begin:begin + batch]]
            current = make_wbc_batch(part, vocab=vocab, level=level, maxlen=maxlen, device=device)
            model.train()
            optimizer.zero_grad(set_to_none=True)
            loss, _, labels, _ = wbc_loss(model, current)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            step += 1
            positive_ratio = float((labels > 0.5).to(torch.float32).mean()) if labels.numel() else 0.0
            curve.append({"step": step, "wbc_loss": float(loss.detach()), "positive_ratio": positive_ratio})
    return curve


def base_context(vocab: Vocab) -> tuple[list[int], list[int]]:
    tokens = [vocab.id("BOS"), vocab.id("SEP_PROFILE"), vocab.id("AGE_NA"), vocab.id("CLUB_NA"),
              vocab.id("NEWS_NA"), vocab.id("FN_NA"), vocab.id("ACTIVE_NA"), vocab.id("SEP_REQUEST"),
              vocab.id("DOW_0"), vocab.id("MONTH_1"), vocab.id("SEP_HISTORY"), vocab.id("SEP_PAGE")]
    return tokens, [-1] * len(tokens)


class WbcTest(unittest.TestCase):
    def setUp(self):
        self.vocab = real_vocab()
        self.ctx, self.ctx_content = base_context(self.vocab)

    def test_item_and_row_labels_and_weights(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001", "0000000004"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=2.0, w_neg=1.0)
        self.assertEqual(exposure.page_tokens,
                         [self.vocab.id("ROW_S1"), self.vocab.item("0000000001"),
                          self.vocab.item("0000000002"), self.vocab.item("0000000003")])
        # 행: 산 상품 1개 → 라벨 1, 가중치 1×2.0 / 상품: 1은 양성(2.0), 2·3은 음성(1.0)
        self.assertEqual(exposure.labels, [1.0, 1.0, 0.0, 0.0])
        self.assertEqual(exposure.weights, [2.0, 2.0, 1.0, 1.0])

    def test_row_weight_scales_with_purchased_items(self):
        rows = [GeneratedRow(self.vocab.id("ROW_REPEAT"), ["0000000001", "0000000002"])]
        exposure = exposure_from_rows(rows, ["0000000001", "0000000002"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=3.0, w_neg=1.0)
        self.assertEqual(exposure.labels, [1.0, 1.0, 1.0])
        self.assertEqual(exposure.weights, [6.0, 3.0, 3.0])

    def test_row_and_item_without_purchase_are_negative(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=5.0, w_neg=2.0)
        self.assertEqual(exposure.labels, [0.0, 0.0, 0.0])
        self.assertEqual(exposure.weights, [2.0, 2.0, 2.0])

    def test_loss_mask_covers_only_generated_token_positions(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab, self.ctx, self.ctx_content)
        batch = make_wbc_batch([exposure], vocab=self.vocab, level="full", maxlen=64, device="cpu")
        self.assertEqual(int(batch["mask"].sum()), len(exposure.page_tokens))
        positions = torch.nonzero(batch["mask"][0]).flatten().tolist()
        self.assertEqual(positions, list(range(len(self.ctx) - 1,
                                               len(self.ctx) - 1 + len(exposure.page_tokens))))
        self.assertEqual(batch["target"][0][batch["mask"][0]].tolist(), exposure.page_tokens)

    def test_training_makes_positive_item_logits_higher(self):
        torch.manual_seed(0)
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab, self.ctx, self.ctx_content)
        exposures = [exposure for _ in range(64)]
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                          dropout=0.0, maxlen=64, content_dim=384)
        model = GenPageV2(cfg, tokens=self.vocab.tokens)
        train(model, exposures, vocab=self.vocab, level="full", maxlen=64, device="cpu",
              epochs=40, batch=16, lr=0.1, warmup=5, eval_every=0)
        batch = make_wbc_batch([exposure], vocab=self.vocab, level="full", maxlen=64, device="cpu")
        model.eval()
        with torch.no_grad():
            _, logits, labels, _ = wbc_loss(model, batch)
            item = batch["is_item"][batch["mask"]]
            positive = float(logits[item & (labels > 0.5)].mean())
            negative = float(logits[item & (labels <= 0.5)].mean())
        self.assertGreater(positive, negative)

    def test_weighted_auc_ranks_and_ties(self):
        self.assertAlmostEqual(weighted_auc([2.0, 1.0], [1.0, 0.0], [1.0, 1.0]), 1.0)
        self.assertAlmostEqual(weighted_auc([1.0, 2.0], [1.0, 0.0], [1.0, 1.0]), 0.0)
        self.assertAlmostEqual(weighted_auc([1.0, 1.0], [1.0, 0.0], [1.0, 1.0]), 0.5)
        self.assertIsNone(weighted_auc([1.0, 1.0], [1.0, 1.0], [1.0, 1.0]))

    def test_run_saves_checkpoint_readable_by_evaluate_decoder(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            content_dir = base / "hm" / "model" / "genpage2" / "content"
            mode_dir.mkdir(parents=True)
            content_dir.mkdir(parents=True)
            vocab = real_vocab()
            vocab.save(mode_dir / "vocab.json")
            save_content(content_dir, np.zeros((len(ARTICLES), 384), dtype=np.float32), ARTICLES)
            context, content = base_context(vocab)
            truth = ["0000000001", "0000000004"]
            history = ["0000000001", "0000000002", "0000000003"]
            self._write_split(mode_dir, "train", [context] * 4, [content] * 4, [history] * 4, [truth] * 4)
            self._write_split(mode_dir, "eval", [context] * 2, [content] * 2, [history] * 2, [truth] * 2)

            cfg = ModelConfig(vocab_size=len(vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                              dropout=0.0, maxlen=64, content_dim=384)
            torch.manual_seed(0)
            init = GenPageV2(cfg, torch.zeros((len(ARTICLES), 384)), tokens=vocab.tokens)
            init_dir = base / "init"
            save_checkpoint(init_dir, init, cfg, {"context": "full"})

            output = base / "wbc-ckpt"
            report = run(argparse.Namespace(
                mode="validate", init=str(init_dir), examples=4, pin_repeat=True, epochs=1,
                batch=2, lr=1e-4, warmup=1, w_pos=1.0, w_neg=1.0, name="wbc-smoke", device="cpu",
                gen_batch=2, data_dir=str(base), out=str(output), eval_every=1, eval_examples=2,
                log_every=1, seed=7,
            ))
            self.assertEqual(report["violations"], 0)
            saved = json.loads((output / "config.json").read_text(encoding="utf-8"))["extra"]
            for key in ("init", "examples", "pin_repeat", "positive_ratio", "loss_curve", "context",
                        "generation_seconds", "train_seconds"):
                self.assertIn(key, saved)
            self.assertEqual(saved["context"], "full")
            self.assertTrue(saved["examples"] > 0)

            decoder, _, _, _, _ = _load_decoder(mode_dir, output, "cpu")
            examples = [{"ctx_tokens": context, "ctx_content": content, "history_articles": []}
                        for _ in range(2)]
            pages = decoder.generate_batch(examples, n_rows=6, items_per_row=8, prefix=2)
            self.assertTrue(all(bad == 0 for _, bad in pages))
            self.assertTrue(all(rows for rows, _ in pages))

    def test_default_settings_reproduce_legacy_loss_curve(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content)
        exposures = [exposure] * 16

        plain = small_model(vocab, seed=0)
        legacy = legacy_wbc_curve(plain, exposures, vocab=vocab, level="full", maxlen=64, device="cpu",
                                  epochs=2, batch=4, lr=0.05, warmup=1, seed=7)
        with tempfile.TemporaryDirectory() as temporary:
            trained = small_model(vocab, seed=0)
            result = train(trained, exposures, vocab=vocab, level="full", maxlen=64, device="cpu",
                           epochs=2, batch=4, lr=0.05, warmup=1, log_every=1, eval_every=0, seed=7,
                           output=temporary)
            curve = result["loss_curve"]
        self.assertEqual(len(curve), len(legacy))
        for current, reference in zip(curve, legacy):
            self.assertEqual(current["step"], reference["step"])
            self.assertAlmostEqual(current["wbc_loss"], reference["wbc_loss"], places=6)
            self.assertAlmostEqual(current["positive_ratio"], reference["positive_ratio"], places=6)
            self.assertNotIn("pretrain_loss", current)

    def test_default_generation_is_greedy_regardless_of_generator(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        page = [vocab.id("EOS")]
        data = ExampleData([(np.asarray(ctx), np.asarray(ctx_content), np.asarray(page)) for _ in range(6)])
        histories = [MANY_ARTICLES[:10] for _ in range(6)]
        truths = [[MANY_ARTICLES[0]] for _ in range(6)]
        content_rows = {article: index for index, article in enumerate(MANY_ARTICLES)}
        decoder = PageDecoder(small_model(vocab, seed=0), vocab, content_rows, "cpu", level="full")
        greedy, _ = _generate_exposures(
            decoder, data, list(range(6)), histories, truths, vocab=vocab, pin_repeat=True, gen_batch=3,
            w_pos=1.0, w_neg=1.0, temperature=0.0, generator=None)
        seeded, violations = _generate_exposures(
            decoder, data, list(range(6)), histories, truths, vocab=vocab, pin_repeat=True, gen_batch=3,
            w_pos=1.0, w_neg=1.0, temperature=0.0, generator=torch.Generator().manual_seed(11))
        self.assertEqual(violations, 0)
        self.assertEqual([item.page_tokens for item in greedy], [item.page_tokens for item in seeded])

    def test_negative_samples_exclude_purchased_and_exposed_products(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        exposed = MANY_ARTICLES[:3]
        rows = [GeneratedRow(vocab.id("ROW_S1"), exposed)]
        truth = [MANY_ARTICLES[0], MANY_ARTICLES[7]]
        exposure = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=4, neg_weight=1.0,
                                      neg_generator=np.random.default_rng(0))
        exposed_tokens = {vocab.item(article) for article in exposed}
        bought_tokens = {vocab.item(article) for article in truth}
        self.assertEqual(exposure.negatives[0], [])
        total = 0
        for negatives in exposure.negatives[1:]:
            self.assertEqual(len(negatives), 4)
            for token in negatives:
                self.assertNotIn(token, exposed_tokens)
                self.assertNotIn(token, bought_tokens)
                total += 1
        self.assertEqual(total, len(exposed) * 4)
        self.assertAlmostEqual(exposure.negative_weight, 0.25)

    def test_negative_samples_drop_for_row_tokens_when_k_is_zero(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content)
        self.assertEqual(len(exposure.negatives), len(exposure.page_tokens))
        self.assertTrue(all(not negatives for negatives in exposure.negatives))
        self.assertEqual(exposure.negative_weight, 0.0)

    def test_negatives_do_not_touch_input_sequence(self):
        """K>0 이어도 입력 시퀀스와 노출 토큰 자리는 K=0 과 완전히 같아야 한다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        truth = [MANY_ARTICLES[0], MANY_ARTICLES[7]]
        plain = exposure_from_rows(rows, truth, vocab, ctx, ctx_content)
        with_negatives = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=4,
                                            neg_weight=1.0, neg_generator=np.random.default_rng(0))
        base = make_wbc_batch([plain], vocab=vocab, level="full", maxlen=64, device="cpu")
        turned = make_wbc_batch([with_negatives], vocab=vocab, level="full", maxlen=64, device="cpu")
        self.assertEqual(base["tokens"].shape, turned["tokens"].shape)
        for key in ("tokens", "content", "mask", "target", "label", "weight", "is_item"):
            self.assertTrue(torch.equal(base[key], turned[key]), key)

    def test_negative_positions_are_item_positions(self):
        """음성은 노출 상품 위치(is_item)에만 있고 나머지 자리는 PAD 다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        occupied = batch["neg_mask"].any(dim=-1)
        self.assertTrue(torch.equal(occupied, batch["is_item"]))
        self.assertGreater(int(batch["neg_mask"].sum()), 0)
        empty = ~batch["neg_mask"]
        self.assertTrue(bool((batch["neg_target"][empty] == vocab.id("PAD")).all()))

    def test_negative_logits_use_same_position_hidden(self):
        """음성 로짓은 노출 상품과 같은 자리 은닉으로 계산돼 어휘 로짓과 일치한다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        model = small_model(vocab, seed=0)
        model.eval()
        with torch.no_grad():
            logits, _, _, neg_logits, neg_labels, neg_weights = _wbc_terms(model, batch)
            full = model.logits(model(batch["tokens"], batch["content"])[:, :-1])
            negative = batch["neg_mask"]
            batch_index, position, _ = torch.nonzero(negative, as_tuple=True)
            expected = full[batch_index, position, batch["neg_target"][negative]]
        self.assertEqual(logits.shape[0], int(batch["mask"].sum()))
        self.assertEqual(neg_logits.shape[0], int(negative.sum()))
        torch.testing.assert_close(neg_logits, expected)
        self.assertTrue(bool((neg_labels == 0).all()))
        self.assertTrue(bool((neg_weights == exposure.negative_weight).all()))

    def test_negative_loss_is_recorded_separately(self):
        """학습 곡선의 wbc_loss 는 노출 항만, 음성 항은 neg_loss 로 따로 남는다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        model = small_model(vocab, seed=0)
        with tempfile.TemporaryDirectory() as temporary:
            result = train(model, [exposure] * 16, vocab=vocab, level="full", maxlen=64, device="cpu",
                           epochs=2, batch=4, lr=0.01, warmup=1, log_every=1, eval_every=0, seed=7,
                           output=temporary)
        curve = result["loss_curve"]
        self.assertTrue(curve)
        self.assertTrue(all("wbc_loss" in entry and "neg_loss" in entry for entry in curve))
        self.assertTrue(all(entry["neg_loss"] is not None for entry in curve))
        self.assertNotIn("pretrain_loss", curve[0])

    def test_evaluation_loss_ignores_negatives(self):
        """검증 손실은 음성을 빼고 노출 토큰만으로 계산한다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        model = small_model(vocab, seed=0)
        report = evaluate_wbc(model, [exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        model.eval()
        with torch.no_grad():
            logits, labels, weights, _, _, _ = _wbc_terms(model, batch)
            exposed = float(torch.nn.functional.binary_cross_entropy_with_logits(logits, labels, weight=weights))
        self.assertAlmostEqual(report["wbc_loss"], exposed, places=5)

    def test_k_zero_loss_matches_element_mean(self):
        """K=0 이면 새 손실이 기존 정의(가중 원소 평균)와 수치가 같다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content)
        batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        model = small_model(vocab, seed=0)
        with torch.no_grad():
            loss, logits, labels, weights = wbc_loss(model, batch)
            legacy = torch.nn.functional.binary_cross_entropy_with_logits(logits, labels, weight=weights)
        self.assertAlmostEqual(float(loss), float(legacy), places=6)

    def test_exposure_term_and_gradient_are_independent_of_k(self):
        """K 가 커져도 노출 항의 값과 그래디언트가 K=0 과 같다(노출 항만 역전파)."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        truth = [MANY_ARTICLES[0]]
        plain = exposure_from_rows(rows, truth, vocab, ctx, ctx_content)
        with_negatives = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=64,
                                            neg_weight=1.0, neg_generator=np.random.default_rng(0))
        model = small_model(vocab, seed=0)
        model.eval()

        def exposure_term_and_grad(exposure):
            batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
            positions = int(batch["mask"].sum())
            model.zero_grad(set_to_none=True)
            logits, labels, weights, _, _, _ = _wbc_terms(model, batch)
            term = _weighted_sum(logits, labels, weights) / positions
            term.backward()
            grads = [parameter.grad.detach().flatten() for parameter in model.parameters()
                     if parameter.grad is not None]
            return float(term.detach()), torch.cat(grads)

        base_value, base_grad = exposure_term_and_grad(plain)
        big_k_value, big_k_grad = exposure_term_and_grad(with_negatives)
        self.assertAlmostEqual(base_value, big_k_value, places=6)
        torch.testing.assert_close(base_grad, big_k_grad)

    def test_exposure_and_negative_terms_sum_to_objective(self):
        """노출 항 + 음성 항 = 최적화 손실, 분모는 노출 자리 수(원소 수가 아니다)."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        batch = make_wbc_batch([exposure], vocab=vocab, level="full", maxlen=64, device="cpu")
        model = small_model(vocab, seed=0)
        model.eval()
        with torch.no_grad():
            loss, exposure_term, negative_term, logits, labels, weights = _wbc_objective(model, batch)
            _, _, _, neg_logits, neg_labels, neg_weights = _wbc_terms(model, batch)
            positions = int(batch["mask"].sum())
            combined = _weighted_sum(torch.cat([logits, neg_logits]), torch.cat([labels, neg_labels]),
                                     torch.cat([weights, neg_weights])) / positions
            element_mean = torch.nn.functional.binary_cross_entropy_with_logits(
                torch.cat([logits, neg_logits]), torch.cat([labels, neg_labels]),
                weight=torch.cat([weights, neg_weights]))
            public, _, _, _ = wbc_loss(model, batch)
        self.assertIsNotNone(negative_term)
        self.assertAlmostEqual(float(exposure_term) + float(negative_term), float(loss), places=6)
        self.assertAlmostEqual(float(loss), float(combined), places=6)
        self.assertAlmostEqual(float(public), float(loss), places=6)
        # 원소 수로 나누는 옛 방식이면 노출 항이 1/(1+K) 로 줄어든다.
        self.assertNotAlmostEqual(float(loss), float(element_mean), places=4)

    def test_curve_terms_sum_to_optimized_loss(self):
        """λ=0 이면 곡선의 wbc_loss + neg_loss 가 실제 최적화한 손실과 같다."""
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content, neg_samples=4,
                                      neg_weight=1.0, neg_generator=np.random.default_rng(0))
        model = small_model(vocab, seed=0)
        with tempfile.TemporaryDirectory() as temporary:
            result = train(model, [exposure] * 8, vocab=vocab, level="full", maxlen=64, device="cpu",
                           epochs=1, batch=4, lr=0.01, warmup=1, log_every=1, eval_every=0, seed=7,
                           output=temporary)
        curve = result["loss_curve"]
        self.assertTrue(curve)
        self.assertTrue(all("neg_loss" in entry for entry in curve))
        self.assertAlmostEqual(curve[-1]["wbc_loss"] + curve[-1]["neg_loss"], result["last_loss"], places=6)

    def test_same_seed_repeats_negatives_and_sampled_exposures(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        truth = [MANY_ARTICLES[0]]
        first = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=3, neg_weight=1.0,
                                   neg_generator=np.random.default_rng(5))
        again = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=3, neg_weight=1.0,
                                   neg_generator=np.random.default_rng(5))
        other = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=3, neg_weight=1.0,
                                   neg_generator=np.random.default_rng(6))

        def flatten(table):
            return [token for negatives in table for token in negatives]

        self.assertEqual(flatten(first.negatives), flatten(again.negatives))
        self.assertNotEqual(flatten(first.negatives), flatten(other.negatives))
        page = [vocab.id("EOS")]
        data = ExampleData([(np.asarray(ctx), np.asarray(ctx_content), np.asarray(page)) for _ in range(6)])
        histories = [MANY_ARTICLES[:10] for _ in range(6)]
        truths = [[MANY_ARTICLES[0]] for _ in range(6)]
        content_rows = {article: index for index, article in enumerate(MANY_ARTICLES)}
        decoder = PageDecoder(small_model(vocab, seed=0), vocab, content_rows, "cpu", level="full")

        def sample(seed):
            return _generate_exposures(decoder, data, list(range(6)), histories, truths, vocab=vocab,
                                       pin_repeat=True, gen_batch=3, w_pos=1.0, w_neg=1.0, temperature=1.0,
                                       generator=torch.Generator().manual_seed(seed), neg_samples=2,
                                       neg_weight=1.0, neg_generator=np.random.default_rng(seed))

        seeded, violations = sample(2)
        repeated, _ = sample(2)
        different, other_violations = sample(3)
        self.assertEqual(violations, 0)
        self.assertEqual(other_violations, 0)
        self.assertEqual([item.page_tokens for item in seeded], [item.page_tokens for item in repeated])
        self.assertEqual([item.negatives for item in seeded], [item.negatives for item in repeated])
        self.assertNotEqual([item.page_tokens for item in seeded], [item.page_tokens for item in different])

    def test_negative_sampling_lowers_unexposed_item_logits(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        truth = [MANY_ARTICLES[0]]
        plain = exposure_from_rows(rows, truth, vocab, ctx, ctx_content)
        with_negatives = exposure_from_rows(rows, truth, vocab, ctx, ctx_content, neg_samples=4,
                                            neg_weight=1.0, neg_generator=np.random.default_rng(0))
        model_plain = small_model(vocab, seed=0)
        model_negatives = small_model(vocab, seed=0)
        train(model_plain, [plain] * 64, vocab=vocab, level="full", maxlen=64, device="cpu",
              epochs=40, batch=16, lr=0.1, warmup=5, eval_every=0)
        train(model_negatives, [with_negatives] * 64, vocab=vocab, level="full", maxlen=64, device="cpu",
              epochs=40, batch=16, lr=0.1, warmup=5, eval_every=0)
        batch = make_wbc_batch([plain], vocab=vocab, level="full", maxlen=64, device="cpu")
        unexposed = [vocab.item(article) for article in MANY_ARTICLES[3:]]
        self.assertLess(item_logits(model_negatives, batch, unexposed),
                        item_logits(model_plain, batch, unexposed))

    def test_pretrain_weight_records_pretrain_loss_separately(self):
        vocab = wide_vocab()
        ctx, ctx_content = base_context(vocab)
        rows = [GeneratedRow(vocab.id("ROW_S1"), MANY_ARTICLES[:3])]
        exposure = exposure_from_rows(rows, [MANY_ARTICLES[0]], vocab, ctx, ctx_content)
        page = [vocab.id("ROW_S1"), vocab.item(MANY_ARTICLES[0]), vocab.id("EOS")]
        pretrain = [(np.asarray(ctx), np.asarray(ctx_content), np.asarray(page)) for _ in range(8)]
        model = small_model(vocab, seed=0)
        with tempfile.TemporaryDirectory() as temporary:
            result = train(model, [exposure] * 16, vocab=vocab, level="full", maxlen=64, device="cpu",
                           epochs=2, batch=4, lr=0.01, warmup=1, log_every=1, eval_every=0, seed=7,
                           pretrain_weight=1.0, pretrain_data=pretrain, output=temporary)
        curve = result["loss_curve"]
        self.assertTrue(curve)
        self.assertTrue(all("pretrain_loss" in entry and "total_loss" in entry for entry in curve))
        self.assertIsNotNone(result["last_pretrain_loss"])

    def _write_split(self, mode_dir: Path, name: str, contexts, contents, histories, truths) -> None:
        tokens = np.concatenate([np.asarray(value, dtype=np.int32) for value in contexts])
        rows = np.concatenate([np.asarray(value, dtype=np.int32) for value in contents])
        offsets = np.zeros(len(contexts) + 1, dtype=np.int64)
        offsets[1:] = np.cumsum([len(value) for value in contexts])
        page = np.concatenate([np.asarray([self.vocab.id("EOS")], dtype=np.int32) for _ in contexts])
        page_offsets = np.arange(0, len(contexts) + 1, dtype=np.int64)
        np.savez(mode_dir / f"{name}.npz", ctx_tokens=tokens, ctx_offsets=offsets, ctx_content=rows,
                 page_tokens=page, page_offsets=page_offsets)
        pd.DataFrame({"customer_id": [f"c{index}" for index in range(len(contexts))],
                      "request_date": [pd.Timestamp("2020-09-09")] * len(contexts),
                      "truth": truths, "history": histories}).to_parquet(mode_dir / f"{name}_meta.parquet")


if __name__ == "__main__":
    unittest.main()
