from __future__ import annotations

import argparse
import json
import random
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.genpage_activity_items import sample_ids, vocab_article_ids
from tools.genpage_capacity import DEFAULT_URL, body, parse_args, parse_threads, resolve_url


def write_vocab(root: Path, name: str, payload: dict) -> Path:
    path = root / name
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


class ActivityItemsTest(unittest.TestCase):
    """활동 상품을 뽑는 어휘 읽기 — v1(`items`)과 v2(`tokens`)가 같은 뜻을 내야 한다."""

    def test_v1_vocab_items_are_integers(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_vocab(Path(temporary), "v1.json", {"items": ["3", "1", "2", "1"]})
            self.assertEqual(vocab_article_ids(path), [1, 2, 3])

    def test_v2_vocab_tokens_lose_the_item_prefix_and_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_vocab(Path(temporary), "v2.json", {"tokens": [
                "PAD", "ITEM_FALLBACK", "ROW_REPEAT", "ROW_S1", "ITEM_0000000007", "ITEM_0000000002"]})
            self.assertEqual(vocab_article_ids(path), [2, 7])

    def test_unknown_vocab_shape_is_an_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_vocab(Path(temporary), "other.json", {"article_rows": {}})
            with self.assertRaises(KeyError):
                vocab_article_ids(path)

    def test_sample_ids_keeps_order_and_size(self):
        ids = list(range(1, 1001))
        chosen = sample_ids(ids, 400)
        self.assertEqual(len(chosen), 400)
        self.assertEqual(chosen[:3], [1, 3, 5])
        self.assertEqual(sample_ids(ids[:10], 400), ids[:10])


class CapacityToolTest(unittest.TestCase):
    """용량 도구의 URL 인자 · 부하 모양(#316 에서 URL 을 인자로 만들었다)."""

    def test_url_precedence_is_argument_then_environment_then_default(self):
        self.assertEqual(resolve_url("http://127.0.0.1:8766/", {}), "http://127.0.0.1:8766")
        self.assertEqual(resolve_url(None, {"MODEL_URL": "http://model:9000"}), "http://model:9000")
        self.assertEqual(resolve_url(None, {}), DEFAULT_URL)

    def test_positional_form_still_works_and_threads_are_configurable(self):
        default = parse_args(["items.json", "out.json"])
        self.assertEqual((default.seconds, default.url, default.threads), (20.0, None, (1, 2, 4, 8)))
        given = parse_args(["items.json", "out.json", "5", "--url", "http://127.0.0.1:8766",
                            "--threads", "1 2 4"])
        self.assertEqual((given.seconds, given.url, given.threads), (5.0, "http://127.0.0.1:8766", (1, 2, 4)))

    def test_threads_must_be_positive(self):
        self.assertEqual(parse_threads("2"), (2,))
        with self.assertRaises(argparse.ArgumentTypeError):
            parse_threads("0")

    def test_page_body_matches_the_application_defaults(self):
        items = list(range(1, 101))
        page = body("/page", random.Random(0), items)
        self.assertEqual(set(page), {"history", "exclude", "exclude_categories", "rows", "items_per_row", "prefix"})
        self.assertEqual((len(page["history"]), len(page["exclude"])), (20, 24))
        self.assertEqual((page["rows"], page["items_per_row"], page["prefix"]), (3, 8, 2))
        recommend = body("/recommend", random.Random(0), items)
        self.assertEqual((len(recommend["history"]), recommend["k"]), (20, 12))


if __name__ == "__main__":
    unittest.main()
