import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from genpage2.content import article_text, embed, load_content, save_content


class ArticleTextTest(unittest.TestCase):
    def test_template_order_and_whitespace(self):
        row = {
            "prod_name": "  Strap   top ",
            "product_type_name": "Vest top",
            "product_group_name": "Garment Upper body",
            "colour_group_name": "Black",
            "perceived_colour_value_name": "Dark",
            "section_name": "Womens Everyday Basics",
            "garment_group_name": "Jersey Basic",
            "index_name": "Ladieswear",
            "detail_desc": "Jersey top with narrow shoulder straps.",
        }
        self.assertEqual(
            article_text(row),
            "passage: Strap top. Vest top, Garment Upper body. Black, Dark. "
            "Womens Everyday Basics, Jersey Basic, Ladieswear. Jersey top with narrow shoulder straps.",
        )

    def test_missing_chunk_is_removed(self):
        row = {
            "prod_name": "Trousers",
            "product_type_name": "Trousers",
            "product_group_name": "Garment Lower body",
            "colour_group_name": "Black",
            "perceived_colour_value_name": "Dark",
            "section_name": "Menswear",
            "garment_group_name": "Trousers",
            "index_name": "Menswear",
            "detail_desc": None,
        }
        self.assertEqual(
            article_text(row),
            "passage: Trousers. Trousers, Garment Lower body. Black, Dark. "
            "Menswear, Trousers, Menswear.",
        )

        row["product_group_name"] = None
        self.assertEqual(
            article_text(row),
            "passage: Trousers. Trousers. Black, Dark. Menswear, Trousers, Menswear.",
        )

    def test_pandas_na_and_nan_chunks_are_removed(self):
        row = {
            "prod_name": "Trousers",
            "product_type_name": "Trousers",
            "product_group_name": np.nan,
            "colour_group_name": "Black",
            "perceived_colour_value_name": "Dark",
            "section_name": "Menswear",
            "garment_group_name": "Trousers",
            "index_name": "Menswear",
            "detail_desc": pd.NA,
        }
        text = article_text(row)
        self.assertEqual(
            text,
            "passage: Trousers. Trousers. Black, Dark. Menswear, Trousers, Menswear.",
        )
        self.assertNotIn("<NA>", text)
        self.assertNotIn("nan", text.lower())


class ContentStorageTest(unittest.TestCase):
    def test_save_and_load_round_trip(self):
        vectors = np.arange(3 * 384, dtype=np.float32).reshape(3, 384)
        article_ids = ["0000000001", "0000000002", "0000000003"]
        with tempfile.TemporaryDirectory() as temp_dir:
            save_content(temp_dir, vectors, article_ids, elapsed_seconds=1.25, device="cpu")
            loaded, rows = load_content(temp_dir)
            self.assertEqual(loaded.dtype, np.float16)
            self.assertEqual(loaded.shape, (3, 384))
            np.testing.assert_array_equal(loaded, vectors.astype(np.float16))
            self.assertEqual(rows, {article_id: index for index, article_id in enumerate(article_ids)})
            metadata = json.loads(Path(temp_dir, "content_meta.json").read_text(encoding="utf-8"))
            self.assertEqual(metadata["model"], "intfloat/multilingual-e5-small")
            self.assertEqual(metadata["count"], 3)


class ContentEmbeddingTest(unittest.TestCase):
    def test_real_model_embedding(self):
        texts = [
            "passage: red cotton dress for women",
            "passage: women's dress made from red cotton",
            "passage: a football match in a large stadium",
        ]
        vectors = embed(texts, batch=3, device="cpu")
        self.assertEqual(vectors.shape, (3, 384))
        np.testing.assert_allclose(np.linalg.norm(vectors, axis=1), 1.0, rtol=1e-4, atol=1e-5)
        similar = float(np.dot(vectors[0], vectors[1]))
        unrelated = float(np.dot(vectors[0], vectors[2]))
        self.assertGreater(similar, unrelated)


if __name__ == "__main__":
    unittest.main()
