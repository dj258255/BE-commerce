package com.beomsu.becommerce.recommendation.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 모델 서버 요청 본문을 <b>길이가 정해진 바이트</b>로 만든다(#254).
 *
 * <p>{@code RestClient} 에 Map 을 그대로 주면 길이를 모른 채 {@code Transfer-Encoding: chunked} 로 보낸다. 모델 서버(파이썬 표준
 * HTTP 서버)는 chunked 를 풀지 않고 {@code Content-Length} 만 읽어서 본문을 빈 것으로 봤다. 그래서 #238 부터 모든 사용자가
 * "이력 없음"으로 생성됐고 오류는 없었다. 바이트로 주면 길이가 붙는다.
 */
final class ModelRequestBody {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelRequestBody() {
    }

    static byte[] of(Map<String, ?> body) {
        try {
            return JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("모델 요청 직렬화 실패", e);
        }
    }
}
