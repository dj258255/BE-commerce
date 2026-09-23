package com.beomsu.becommerce.home;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 홈 다음 쪽 커서(#237, ADR-052). <b>이미 보여 준 상품과 행을 커서가 들고 다닌다.</b>
 *
 * <p>서버에 쪽 상태를 두지 않은 이유: 쪽마다 Redis 에 쓰면 TTL 이 지난 뒤 중복이 돌아오고, Redis 장애가
 * 홈 아래쪽을 건드리고, 인스턴스마다 같은 상태를 봐야 한다. 커서에 실으면 어느 인스턴스로 가도 같은 답이
 * 나온다. 대가는 커서가 쪽마다 커진다는 것이고(상품 id 하나에 base36 약 7자), 클라이언트가 고칠 수 있다는
 * 것이다. 고쳐서 얻는 것은 <b>자기 화면의 중복</b>뿐이라 서명하지 않았다.
 *
 * <p>길이 상한을 넘거나 모양이 틀린 커서는 {@link IllegalArgumentException} — API 는 400 으로 답한다.
 */
public record HomeCursor(int page, Set<Long> shown, Set<String> usedRows) {

    /** 인코딩된 커서 길이 상한. 쪽마다 상품 24개가 늘면 약 10쪽까지 들어간다. */
    public static final int MAX_ENCODED_LENGTH = 4096;
    private static final String VERSION = "v1";

    public HomeCursor {
        shown = Set.copyOf(shown);
        usedRows = Set.copyOf(usedRows);
    }

    public static HomeCursor first() {
        return new HomeCursor(1, Set.of(), Set.of());
    }

    /** 이 쪽에서 보여 준 것을 더한 다음 쪽 커서. */
    public HomeCursor next(Iterable<Long> newlyShown, Iterable<String> newRows) {
        Set<Long> ids = new LinkedHashSet<>(shown);
        newlyShown.forEach(ids::add);
        Set<String> rows = new LinkedHashSet<>(usedRows);
        newRows.forEach(rows::add);
        return new HomeCursor(page + 1, ids, rows);
    }

    public String encode() {
        List<String> ids = new ArrayList<>();
        shown.stream().sorted().forEach(id -> ids.add(Long.toString(id, 36)));
        String raw = VERSION + ";" + page + ";" + String.join(",", ids) + ";" + String.join(",", usedRows.stream().sorted().toList());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static HomeCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return first();
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("커서가 너무 길다: " + encoded.length());
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("커서를 읽을 수 없다", e);
        }
        String[] parts = raw.split(";", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("커서 모양이 틀렸다");
        }
        try {
            int page = Integer.parseInt(parts[1]);
            if (page < 2) {
                throw new IllegalArgumentException("다음 쪽 커서는 2쪽부터다");
            }
            Set<Long> shown = new LinkedHashSet<>();
            for (String id : parts[2].isEmpty() ? new String[0] : parts[2].split(",")) {
                shown.add(Long.parseLong(id, 36));
            }
            Set<String> rows = new LinkedHashSet<>(parts[3].isEmpty() ? List.of() : List.of(parts[3].split(",")));
            return new HomeCursor(page, shown, rows);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("커서 숫자를 읽을 수 없다", e);
        }
    }
}
