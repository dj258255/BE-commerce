package com.beomsu.becommerce.subscription.web;

import com.beomsu.becommerce.subscription.dunning.DunningScheduler;
import com.beomsu.becommerce.subscription.internal.SubscriptionDetailView;
import com.beomsu.becommerce.subscription.internal.SubscriptionService;
import com.beomsu.becommerce.subscription.internal.SubscriptionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * 구독(정기결제) REST 컨트롤러 — subscription 모듈의 사용자 표면.
 *
 * <p>빌링키(암호화 저장)로 구독을 개시하고, 내 구독 조회·해지·청구 이력을 제공한다. 모든 조회·변경은
 * 인증된 사용자 소유의 구독으로 한정한다(IDOR 방지 — {@code principal.getName()}=userId).
 * 실제 정기청구는 {@code DunningScheduler}가 주기 실행하며, 데모/운영 편의로 즉시 청구 트리거를 둔다.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** 구독 개시 — 빌링키 등록 + ACTIVE 구독 생성(첫 청구일 = 오늘 + 1개월). */
    @PostMapping
    public ResponseEntity<SubscriptionView> subscribe(@RequestBody SubscribeRequest request, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        SubscriptionView view = subscriptionService.createSubscription(
                userId, request.billingKey(), request.planAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 내 구독 목록. */
    @GetMapping
    public List<SubscriptionView> mySubscriptions(Principal principal) {
        return subscriptionService.subscriptionsOf(Long.parseLong(principal.getName()));
    }

    /** 구독 상세 + 청구 이력(소유자만). */
    @GetMapping("/{id}")
    public SubscriptionDetailView detail(@PathVariable Long id, Principal principal) {
        return subscriptionService.detail(id, Long.parseLong(principal.getName()));
    }

    /** 구독 해지(소유자만). */
    @PostMapping("/{id}/cancel")
    public SubscriptionView cancel(@PathVariable Long id, Principal principal) {
        return subscriptionService.cancel(id, Long.parseLong(principal.getName()));
    }

    /** 즉시 청구 트리거(데모/운영 — 소유자만). 정기청구는 스케줄러가 주기 실행. */
    @PostMapping("/{id}/bill-now")
    public SubscriptionView billNow(@PathVariable Long id, Principal principal) {
        return subscriptionService.billNow(id, Long.parseLong(principal.getName()));
    }

    /** 구독 개시 요청 — 빌링키(카드 등록으로 발급받은 값)와 구독 금액. */
    public record SubscribeRequest(String billingKey, long planAmount) {
    }
}
