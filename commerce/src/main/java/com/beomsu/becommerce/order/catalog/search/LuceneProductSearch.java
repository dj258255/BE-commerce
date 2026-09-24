package com.beomsu.becommerce.order.catalog.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 앱 프로세스 안의 Lucene 검색.
 *
 * <p>ES·OpenSearch 와 같은 질의를 손으로 짠다 — 필드별로 분석한 단어마다 퍼지 질의를 OR 로 묶고, 필드끼리는
 * 최댓값(dis_max)을 쓴다. 점수 함수는 셋 다 Lucene 기본 BM25 다.
 *
 * <p><b>대가</b>: 색인이 프로세스 메모리에 있다. 인스턴스마다 기동 때 색인을 새로 만들고, 인스턴스끼리
 * 색인을 나눠 쓰지 못한다. 카탈로그가 바뀌면 인스턴스마다 반영해야 한다.
 *
 * <p><b>문서 하나씩 갈아 끼울 수 있다</b>(#246). 쓰기 핸들을 닫지 않고 두고, {@link #upsert}·{@link #delete} 로 바꾼 것은
 * {@link #refreshReader()} 가 검색기를 다시 열 때 보인다(near-real-time). 다시 열기 전까지는 옛 검색기가 답한다.
 */
public class LuceneProductSearch implements ProductSearch, AutoCloseable {

    /** 색인할 한 행. 필터 필드는 검색어와 함께 거는 필터·패싯에 쓴다(#244). 재고는 색인 시점 값이다. */
    public record Doc(long productId, String name, String productType, String description,
                      String categoryCode, String subcategoryCode, String colourCode, long price, boolean inStock) {

        /** 텍스트만 있는 행(필터 필드 없음). */
        public Doc(long productId, String name, String productType, String description) {
            this(productId, name, productType, description, null, null, null, 0L, true);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LuceneProductSearch.class);
    /** 문서를 갈아 끼울 때 찾는 키. 저장 필드 {@code id} 는 색인되지 않아 따로 둔다. */
    private static final String ID_KEY = "id_key";

    private final Analyzer analyzer = new EnglishAnalyzer();
    private final Directory directory = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final long buildMillis;

    public LuceneProductSearch(Iterable<Doc> docs) {
        long started = System.nanoTime();
        try {
            this.writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            for (Doc doc : docs) {
                writer.addDocument(document(doc));
            }
            writer.forceMerge(1);
            writer.commit();
            this.searchers = new SearcherManager(writer, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 색인 실패", e);
        }
        this.buildMillis = (System.nanoTime() - started) / 1_000_000;
    }

    private static Document document(Doc doc) {
        Document d = new Document();
        d.add(new StoredField("id", doc.productId()));
        d.add(new StringField(ID_KEY, Long.toString(doc.productId()), Field.Store.NO));
        d.add(new TextField("name", nullToEmpty(doc.name()), Field.Store.NO));
        d.add(new TextField("product_type", nullToEmpty(doc.productType()), Field.Store.NO));
        d.add(new TextField("description", nullToEmpty(doc.description()), Field.Store.NO));
        keyword(d, "category_code", doc.categoryCode(), false);
        keyword(d, "subcategory_code", doc.subcategoryCode(), false);
        keyword(d, "colour_code", doc.colourCode(), true);
        keyword(d, "product_type_kw", doc.productType(), true);
        d.add(new LongPoint("price", doc.price()));
        d.add(new StringField("in_stock", doc.inStock() ? "1" : "0", Field.Store.NO));
        return d;
    }

    /** 같은 id 의 문서를 갈아 끼운다(없으면 더한다). 검색에 보이는 것은 다음 {@link #refreshReader()} 뒤다. */
    public void upsert(Doc doc) {
        try {
            writer.updateDocument(new Term(ID_KEY, Long.toString(doc.productId())), document(doc));
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 문서 갱신 실패", e);
        }
    }

    public void delete(long productId) {
        try {
            writer.deleteDocuments(new Term(ID_KEY, Long.toString(productId)));
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 문서 삭제 실패", e);
        }
    }

    /** 바뀐 것이 있으면 검색기를 다시 연다. 없으면 싸다(바뀐 것이 없다는 것만 확인한다). */
    public void refreshReader() {
        try {
            searchers.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 검색기 갱신 실패", e);
        }
    }

    /** 검색기를 빌려 쓰고 돌려준다. 다시 열린 뒤에도 빌린 검색기는 돌려줄 때까지 유효하다. */
    private <T> T withSearcher(SearcherWork<T> work) {
        IndexSearcher searcher;
        try {
            searcher = searchers.acquire();
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 검색기 획득 실패", e);
        }
        try {
            return work.apply(searcher);
        } catch (IOException e) {
            throw new UncheckedIOException("Lucene 검색 실패", e);
        } finally {
            try {
                searchers.release(searcher);
            } catch (IOException e) {
                log.debug("Lucene 검색기 반납 실패: {}", e.toString());
            }
        }
    }

    @FunctionalInterface
    interface SearcherWork<T> {
        T apply(IndexSearcher searcher) throws IOException;
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        Query q = toQuery(query);
        if (q == null) {
            return SearchPage.empty();
        }
        return page(q, page, size);
    }

    private SearchPage page(Query q, int page, int size) {
        return withSearcher(searcher -> {
            TopDocs top = searcher.search(q, (page + 1) * size);
            List<Long> ids = new ArrayList<>();
            ScoreDoc[] docs = top.scoreDocs;
            for (int i = page * size; i < docs.length; i++) {
                ids.add(searcher.storedFields().document(docs[i].doc).getField("id").numericValue().longValue());
            }
            return new SearchPage(ids, searcher.count(q));
        });
    }

    @Override
    public boolean filtersInEngine() {
        return true;
    }

    /** 검색어 + 필터. 필터는 점수에 끼지 않는 FILTER 절이다 — 관련도 순서는 검색어가 정한다. */
    @Override
    public SearchPage searchFiltered(String query, SearchFilters filters, int page, int size) {
        Query text = toQuery(query);
        if (text == null) {
            return SearchPage.empty();
        }
        return page(withFilters(text, filters), page, size);
    }

    /** 패싯 — 축마다 <b>자기 축을 뺀</b> 필터로 일치 집합 전체를 센다(상위 몇 개가 아니다). */
    @Override
    public SearchFacets facets(String query, SearchFilters filters) {
        Query text = toQuery(query);
        if (text == null) {
            return new SearchFacets(Map.of(), Map.of());
        }
        return new SearchFacets(countBy(withFilters(text, filters.withoutColour()), "colour_code"),
                countBy(withFilters(text, filters.withoutProductType()), "product_type_kw"));
    }

    static Query withFilters(Query text, SearchFilters f) {
        if (f.isEmpty()) {
            return text;
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder().add(text, BooleanClause.Occur.MUST);
        term(b, "category_code", f.categoryCode());
        term(b, "subcategory_code", f.subcategoryCode());
        term(b, "colour_code", f.colourCode());
        term(b, "product_type_kw", f.productType());
        if (f.minPrice() != null || f.maxPrice() != null) {
            b.add(LongPoint.newRangeQuery("price", f.minPrice() == null ? Long.MIN_VALUE : f.minPrice(),
                    f.maxPrice() == null ? Long.MAX_VALUE : f.maxPrice()), BooleanClause.Occur.FILTER);
        }
        if (f.inStockOnly()) {
            b.add(new TermQuery(new Term("in_stock", "1")), BooleanClause.Occur.FILTER);
        }
        return b.build();
    }

    private static void term(BooleanQuery.Builder b, String field, String value) {
        if (value != null) {
            b.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.FILTER);
        }
    }

    private Map<String, Long> countBy(Query q, String field) {
        return withSearcher(searcher -> searcher.search(q, new CollectorManager<FacetCollector, Map<String, Long>>() {
                @Override
                public FacetCollector newCollector() {
                    return new FacetCollector(field);
                }

                @Override
                public Map<String, Long> reduce(Collection<FacetCollector> collectors) {
                    Map<String, Long> counts = new HashMap<>();
                    collectors.forEach(c -> c.counts.forEach((k, v) -> counts.merge(k, v, Long::sum)));
                    return counts;
                }
            }));
    }

    /** 문서값(SortedDocValues)으로 한 필드의 값별 개수를 센다. 점수는 계산하지 않는다. */
    private static final class FacetCollector extends SimpleCollector {
        private final String field;
        private final Map<String, Long> counts = new HashMap<>();
        private SortedDocValues values;

        FacetCollector(String field) {
            this.field = field;
        }

        @Override
        protected void doSetNextReader(LeafReaderContext context) throws IOException {
            values = DocValues.getSorted(context.reader(), field);
        }

        @Override
        public void collect(int doc) throws IOException {
            if (values.advanceExact(doc)) {
                counts.merge(values.lookupOrd(values.ordValue()).utf8ToString(), 1L, Long::sum);
            }
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }

    private static void keyword(Document d, String field, String value, boolean facet) {
        if (value == null) {
            return;
        }
        d.add(new StringField(field, value, Field.Store.NO));
        if (facet) {
            d.add(new SortedDocValuesField(field, new BytesRef(value)));
        }
    }

    /**
     * ES 쪽 {@link EngineQuery#searchBody} 와 같은 모양 — 정확 일치 dis_max({@value EngineQuery#EXACT_BOOST}배)와
     * 오타 허용 dis_max 를 SHOULD 로 묶는다. 분석 뒤 남는 단어가 없으면 null.
     */
    Query toQuery(String text) {
        List<Query> exact = new ArrayList<>();
        List<Query> fuzzy = new ArrayList<>();
        for (Map.Entry<String, Float> field : EngineQuery.FIELDS.entrySet()) {
            List<String> terms = analyze(field.getKey(), text);
            if (terms.isEmpty()) {
                continue;
            }
            BooleanQuery.Builder exactAny = new BooleanQuery.Builder();
            BooleanQuery.Builder fuzzyAny = new BooleanQuery.Builder();
            for (String term : terms) {
                Term t = new Term(field.getKey(), term);
                int edits = EngineQuery.autoEdits(term);
                exactAny.add(new TermQuery(t), BooleanClause.Occur.SHOULD);
                fuzzyAny.add(edits == 0 ? new TermQuery(t) : new FuzzyQuery(t, edits), BooleanClause.Occur.SHOULD);
            }
            exact.add(new BoostQuery(exactAny.build(), field.getValue()));
            fuzzy.add(new BoostQuery(fuzzyAny.build(), field.getValue()));
        }
        if (exact.isEmpty()) {
            return null;
        }
        return new BooleanQuery.Builder()
                .add(new BoostQuery(new DisjunctionMaxQuery(exact, 0.0f), EngineQuery.EXACT_BOOST), BooleanClause.Occur.SHOULD)
                .add(new DisjunctionMaxQuery(fuzzy, 0.0f), BooleanClause.Occur.SHOULD)
                .build();
    }

    private List<String> analyze(String field, String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(field, text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(attr.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
    }

    /** 벤치마크가 전체 일치 집합을 뽑을 때만 쓴다(#244). */
    <T> T search(SearcherWork<T> work) {
        return withSearcher(work);
    }

    public long buildMillis() {
        return buildMillis;
    }

    /** 지금 검색기에 보이는 문서 수. 지운 문서는 빠진다. */
    public int docCount() {
        return withSearcher(searcher -> searcher.getIndexReader().numDocs());
    }

    /** 색인 파일 크기의 합. 메모리 디렉터리라 이것이 곧 색인이 차지하는 힙이다. */
    public long ramBytes() {
        try {
            long total = 0;
            for (String file : directory.listAll()) {
                total += directory.fileLength(file);
            }
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public String engine() {
        return "lucene";
    }

    @Override
    public boolean ranksByRelevance() {
        return true;
    }

    @Override
    public void close() throws IOException {
        searchers.close();
        writer.close();
        directory.close();
        analyzer.close();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
