package com.beomsu.becommerce.personalization.web;

import com.beomsu.becommerce.personalization.internal.ActivityIngestService;
import com.beomsu.becommerce.personalization.internal.ContextView;
import com.beomsu.becommerce.personalization.internal.OnlineContextReader;
import com.beomsu.becommerce.personalization.internal.PersonalizationException;
import com.beomsu.becommerce.personalization.internal.UserActivity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.Set;

/**
 * 개인화 표면 — 활동 수집과 컨텍스트 읽기.
 *
 * <p>userId는 <b>인증 principal</b>에서 얻는다. 경로·본문으로 받지 않으므로 남의 컨텍스트를
 * 읽거나 남의 활동으로 위조할 방법이 없다(주문·위시리스트와 같은 규칙).
 *
 * <p>두 엔드포인트가 한 컨트롤러에 있는 이유: 하나의 표면(한 사용자의 컨텍스트)이고, 쓰기와 읽기가
 * 같은 데이터를 다룬다. 쪼개면 서로를 참조하는 두 파일이 생긴다.
 */
@RestController
@RequestMapping("/api/v1/personalization")
public class PersonalizationController {

    private static final Set<String> ALLOWED_TYPES = Set.of("CLICK", "VIEW");

    private final ActivityIngestService ingestService;
    private final OnlineContextReader contextReader;

    public PersonalizationController(ActivityIngestService ingestService, OnlineContextReader contextReader) {
        this.ingestService = ingestService;
        this.contextReader = contextReader;
    }

    /**
     * 활동 한 건을 기록한다. <b>합성 생성기용</b>이다 — 실제 화면이 부르는 표면이 아니다.
     * {@code seq}는 클라이언트가 부여하는 사용자별 단조 증가 순번이고, 읽을 때
     * {@code expectSeq}로 같은 값을 써서 "내 이벤트가 반영됐는가"를 묻는다.
     */
    @PostMapping("/activity")
    public ResponseEntity<ActivityView> record(@RequestBody ActivityRequest request, Principal principal) {
        requireValid(request);
        long userId = Long.parseLong(principal.getName());
        UserActivity activity = ingestService.ingest(userId, request.itemId(), request.type(), request.seq());
        return ResponseEntity.status(HttpStatus.CREATED).body(ActivityView.from(activity));
    }

    /**
     * 내 컨텍스트를 읽는다. {@code expectSeq}를 주면 그 순번까지 <b>{@code waitMs} 안에서 기다린다</b>
     * (E1의 대기 정책). 상한을 넘으면 {@code reflected=false}로 정직하게 돌려준다.
     */
    @GetMapping("/context")
    public ContextView context(@RequestParam(required = false) Long expectSeq,
                               @RequestParam(required = false) Long waitMs,
                               Principal principal) {
        return contextReader.read(Long.parseLong(principal.getName()), expectSeq, waitMs);
    }

    private void requireValid(ActivityRequest request) {
        if (request.seq() < 1) {
            throw PersonalizationException.invalidRequest("seq는 1 이상이어야 합니다: " + request.seq());
        }
        if (!ALLOWED_TYPES.contains(request.type())) {
            throw PersonalizationException.invalidRequest("허용되지 않은 활동 유형입니다: " + request.type());
        }
    }

    /** 활동 기록 요청. */
    public record ActivityRequest(long itemId, String type, long seq) {
    }

    /** 활동 기록 응답 — {@code source}로 합성임을 밝힌다. */
    public record ActivityView(long activityId, long userId, long itemId, String type, long seq,
                               String source, Instant occurredAt) {

        static ActivityView from(UserActivity activity) {
            return new ActivityView(activity.getId(), activity.getUserId(), activity.getItemId(),
                    activity.getActivityType(), activity.getSeq(), activity.getSource(),
                    activity.getOccurredAt());
        }
    }
}
