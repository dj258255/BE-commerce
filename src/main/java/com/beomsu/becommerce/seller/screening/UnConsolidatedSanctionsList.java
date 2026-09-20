package com.beomsu.becommerce.seller.screening;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UN 안전보장이사회 통합 제재 명단을 받아 온다.
 *
 * <p><b>왜 UN 인가</b>: 공개 제재 목록 중 <b>기계가 읽을 수 있고 라이선스 제약이 없으면서</b>
 * 생년월일·국적·별칭까지 구조화해 주는 것이 UN 과 OFAC 둘이다. 한국은 UN 안보리 결의 지정자를
 * 자동 반영하므로 국내 서비스에도 그대로 의미가 있다. 국내 금융위 고시는
 * <b>기계가 읽을 수 있는 공개 배포를 못 찾아</b> 여기서는 안 쓴다.
 *
 * <h3>갱신을 코드 배포에서 떼어 놓는다</h3>
 * 명단을 저장소에 실어 두면 <b>낡은 명단이 코드와 함께 굳는다.</b> 갱신 주기가 배포 주기에
 * 묶이면 그것 자체가 규제 위반이다. 그래서 주기적으로 받아 메모리에 올리고,
 * <b>언제 받은 판인지</b>를 {@link #version()} 으로 남긴다 — 나중에 "그때는 통과였다"를 대려면
 * 어느 판과 대조했는지가 있어야 한다.
 *
 * <h3>못 받으면 비운 채로 두지 않는다</h3>
 * 받기에 실패하면 <b>직전 판을 그대로 쓴다.</b> 비우면 {@code SellerScreeningService} 가
 * "명단이 없어 대조하지 못했다"로 전부 보류시켜 지급이 멈춘다. 낡은 명단으로 보는 것이
 * 아무것도 안 보는 것보다 낫고, 그 사실은 판(version)에 남는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.screening.list.un.enabled", havingValue = "true")
public class UnConsolidatedSanctionsList implements SanctionsList {

    private final String url;
    private final Duration timeout;
    private final long staleAfterHours;
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(List.of(), "not-loaded"));

    public UnConsolidatedSanctionsList(
            @Value("${app.screening.list.un.url:https://scsanctions.un.org/resources/xml/en/consolidated.xml}") String url,
            @Value("${app.screening.list.un.timeout-seconds:30}") long timeoutSeconds,
            @Value("${app.screening.list.un.stale-after-hours:48}") long staleAfterHours) {
        this.url = url;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.staleAfterHours = staleAfterHours;
    }

    @Override
    public List<Entry> entries() {
        return snapshot.get().entries();
    }

    @Override
    public String version() {
        return snapshot.get().version();
    }

    /**
     * 지금 들고 있는 명단이 <b>며칠 된 것인가.</b> 못 받으면 직전 판을 그대로 쓰는데,
     * 그것이 30일 전 것이어도 계속 통과시키면 <b>"명단을 보고 있다"가 거짓이 된다.</b>
     * 값을 밖으로 내서 알림이 재게 한다.
     */
    public long ageHours() {
        return java.time.Duration.between(snapshot.get().fetchedAt(), java.time.Instant.now()).toHours();
    }

    /** 이 시간을 넘으면 사람이 봐야 한다. 지급을 막지는 않는다 — 막으면 전면 중단이다. */
    public boolean stale() {
        return ageHours() > staleAfterHours;
    }

    /**
     * 하루 한 번 받는다. OFAC 은 갱신 주기를 정해 두지 않아 <b>갱신될 때마다 즉시 반영</b>이
     * 이상적이지만, 매일 한 번이 업계에서 모범관행으로 다뤄지는 하한선이다.
     */
    @Scheduled(cron = "${app.screening.list.un.cron:0 0 5 * * *}")
    public void refresh() {
        try {
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<InputStream> res = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)   // 서명된 저장소 URL 로 넘어간다
                    .build()
                    .send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                keepPrevious("HTTP " + res.statusCode());
                return;
            }
            var parsed = parse(res.body());
            snapshot.set(parsed);
            log.info("[screening] UN 명단 갱신 entries={} version={}", parsed.entries().size(), parsed.version());
        } catch (Exception e) {
            keepPrevious(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 실패하면 직전 판을 그대로 쓴다. 비우면 지급이 통째로 멈춘다. */
    private void keepPrevious(String why) {
        var cur = snapshot.get();
        long age = ageHours();
        // 낡은 정도를 같이 적는다. "못 받았다"만 남기면 <얼마나 오래 못 받았는지>가 안 남는다.
        if (age > staleAfterHours) {
            log.error("[screening] UN 명단을 {}시간째 못 받았다 — 사람이 봐야 한다 ({}), version={} entries={}",
                    age, why, cur.version(), cur.entries().size());
        } else {
            log.warn("[screening] UN 명단을 못 받았다 — 직전 판을 그대로 쓴다 ({}), {}시간 됨, version={} entries={}",
                    why, age, cur.version(), cur.entries().size());
        }
    }

    /**
     * {@code CONSOLIDATED_LIST/INDIVIDUALS/INDIVIDUAL} 을 읽는다.
     *
     * <p>생년월일은 {@code TYPE_OF_DATE} 가 EXACT · APPROXIMATELY · BETWEEN 셋이라
     * <b>정확한 날짜가 아닐 수 있다.</b> EXACT 가 아니면 안 쓴다 —
     * 어림잡은 생년월일로 점수를 깎으면 <b>맞는 사람을 떨어뜨린다.</b>
     */
    static Snapshot parse(InputStream xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        // 외부 엔티티를 막는다. 남이 주는 XML 이라 이 설정이 없으면 파일을 읽히거나 요청을 대신 보내게 된다.
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);

        var doc = f.newDocumentBuilder().parse(xml);
        var root = doc.getDocumentElement();
        String generated = root.getAttribute("dateGenerated");

        List<Entry> out = new ArrayList<>();
        NodeList people = doc.getElementsByTagName("INDIVIDUAL");
        for (int i = 0; i < people.getLength(); i++) {
            Element e = (Element) people.item(i);
            String name = joinNames(e);
            if (name.isBlank()) continue;
            out.add(new Entry(name, text(e, "UN_LIST_TYPE"), text(e, "NATIONALITY"), exactBirthDate(e)));
        }
        NodeList orgs = doc.getElementsByTagName("ENTITY");
        for (int i = 0; i < orgs.getLength(); i++) {
            Element e = (Element) orgs.item(i);
            String name = text(e, "FIRST_NAME");
            if (name == null || name.isBlank()) continue;
            out.add(new Entry(name, text(e, "UN_LIST_TYPE"), text(e, "NATIONALITY"), null));
        }
        return new Snapshot(List.copyOf(out),
                "UN-" + (generated == null || generated.isBlank() ? "unknown" : generated));
    }

    /** 이름이 네 칸에 나뉘어 온다. 붙여서 하나로 만든다. */
    private static String joinNames(Element e) {
        var sb = new StringBuilder();
        for (String tag : new String[]{"FIRST_NAME", "SECOND_NAME", "THIRD_NAME", "FOURTH_NAME"}) {
            String v = text(e, tag);
            if (v != null && !v.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(v.strip());
            }
        }
        return sb.toString();
    }

    /**
     * EXACT 인 생년월일만 쓰고, <b>주는 만큼만</b> 만든다.
     *
     * <p>처음엔 연·월·일이 다 있을 때만 썼다. 실제 명단을 받아 세어 보니
     * <b>EXACT 751건 중 연월일이 온전한 것은 0건</b>이고 263건이 연도만 준다.
     * 그 조건은 실 데이터에서 <b>한 번도 발동하지 않는다.</b> 손으로 만든 XML 로만 검증했으면
     * 끝까지 몰랐을 자리다.
     *
     * <p>그래서 있는 만큼 잘라 낸다 — {@code 1971} 또는 {@code 1971-04} 또는 {@code 1971-04-02}.
     * 비교하는 쪽이 겹치는 자리까지만 맞대고, 짧을수록 약한 증거로 친다.
     *
     * <p>APPROXIMATELY·BETWEEN 은 안 쓴다. <b>어림잡은 날짜로 점수를 깎으면 맞는 사람이 떨어진다.</b>
     */
    private static String exactBirthDate(Element person) {
        NodeList dobs = person.getElementsByTagName("INDIVIDUAL_DATE_OF_BIRTH");
        for (int i = 0; i < dobs.getLength(); i++) {
            Element d = (Element) dobs.item(i);
            if (!"EXACT".equalsIgnoreCase(text(d, "TYPE_OF_DATE"))) continue;
            String y = text(d, "YEAR");
            if (y == null || y.length() < 4) continue;
            String m = text(d, "MONTH"), day = text(d, "DAY");
            if (m == null) return y;
            if (day == null) return "%s-%02d".formatted(y, Integer.parseInt(m));
            return "%s-%02d-%02d".formatted(y, Integer.parseInt(m), Integer.parseInt(day));
        }
        return null;
    }

    /**
     * 태그 하나의 값을 읽는다.
     *
     * <p><b>값이 {@code <VALUE>} 자식에 한 겹 더 싸여 온다.</b> 실제 명단은
     * {@code <NATIONALITY><VALUE>Chad</VALUE></NATIONALITY>} 모양이라 그냥 텍스트를 뽑으면
     * 줄바꿈과 들여쓰기가 섞여 들어온다. 손으로 만든 XML 에는 그 겹이 없어서 몰랐고,
     * 실제 파일에서 <b>나라 49건을 못 알아보는</b> 것으로 드러났다.
     */
    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        if (n.getLength() == 0) return null;
        Node first = n.item(0);
        for (int i = 0; i < n.getLength(); i++) {
            if (n.item(i).getParentNode() == parent) { first = n.item(i); break; }
        }
        // <VALUE> 로 한 겹 싸여 있으면 그 안을 본다
        if (first instanceof Element el) {
            NodeList v = el.getElementsByTagName("VALUE");
            if (v.getLength() > 0) first = v.item(0);
        }
        String raw = first.getTextContent();
        if (raw == null) return null;
        String v = raw.replaceAll("\\s+", " ").strip();   // 줄바꿈·들여쓰기를 한 칸으로
        return v.isEmpty() ? null : v;
    }

    record Snapshot(List<Entry> entries, String version, java.time.Instant fetchedAt) {
        Snapshot(List<Entry> entries, String version) { this(entries, version, java.time.Instant.now()); }
    }
}
