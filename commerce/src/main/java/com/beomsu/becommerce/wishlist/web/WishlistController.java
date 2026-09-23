package com.beomsu.becommerce.wishlist.web;

import com.beomsu.becommerce.wishlist.internal.WishlistService;
import com.beomsu.becommerce.wishlist.internal.WishlistView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 위시리스트 REST 컨트롤러 — 로그인 사용자의 찜 표면.
 *
 * <p>userId는 <b>인증된 principal</b>에서 얻는다({@code principal.getName()}=userId). 경로·본문으로
 * userId를 받지 않으므로 "남의 위시리스트"를 가리킬 방법이 없다(IDOR 방지가 검증이 아니라
 * 구조로 성립한다). SecurityConfig가 {@code /api/v1/wishlist/**}를 {@code ROLE_USER}로 잠근다.
 *
 * <p>상태 코드: 추가는 <b>200</b>(201이 아니다 — 멱등이라 "생성됨"과 "이미 있었다"를 구분하지
 * 않는다), 삭제는 <b>204</b>(없는 것을 지워도 204).
 */
@RestController
@RequestMapping("/api/v1/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    /** 찜 추가 — 멱등. 없는 상품이면 404 {@code PRODUCT_NOT_FOUND}. */
    @PostMapping
    public WishlistView add(@RequestBody AddWishlistRequest request, Principal principal) {
        return wishlistService.add(Long.parseLong(principal.getName()), request.productId());
    }

    /** 찜 삭제 — 멱등(없어도 204). */
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> remove(@PathVariable long productId, Principal principal) {
        wishlistService.remove(Long.parseLong(principal.getName()), productId);
        return ResponseEntity.noContent().build();
    }

    /** 내 찜 목록 — 최근에 찜한 것이 먼저. 구독 목록과 같이 <b>배열</b>을 그대로 돌려준다. */
    @GetMapping
    public List<WishlistView> myWishlist(Principal principal) {
        return wishlistService.list(Long.parseLong(principal.getName()));
    }

    /** 찜 추가 요청. */
    public record AddWishlistRequest(long productId) {
    }
}
