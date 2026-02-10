package com.example.coupon.controller;

import com.example.coupon.repository.UserRepository;
import com.example.coupon.service.CouponIssueService;
import com.example.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/coupons/")
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;
    private final UserRepository userRepository;

    /**
     * 쿠폰 발급 요청
     * Redis 선차단 후 Kafka로 비동기 처리
     */
    @PostMapping("/{couponId}/issue")
    public ResponseEntity<?> issue(
            @PathVariable Long couponId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        couponService.issueCoupon(userDetails.getUsername(), couponId);
        return ResponseEntity.accepted().build();
    }

    /**
     * 쿠폰 취소
     * DB에서 삭제하고 Redis count 감소
     */
    @DeleteMapping("/{couponId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable Long couponId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = userRepository.findByUsername(userDetails.getUsername())
            .orElseThrow(() -> new RuntimeException("User not found"))
            .getId();
        
        couponIssueService.cancelCoupon(couponId, userId);
        return ResponseEntity.ok().build();
    }
}
