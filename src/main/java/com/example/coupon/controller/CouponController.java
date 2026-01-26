package com.example.coupon.controller;

import com.example.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("api/coupons/")
public class CouponController {

    private final CouponService couponService;

    @PostMapping("/{couponId}/issue")
    public ResponseEntity<?> issue(
            @PathVariable Long couponId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        couponService.issueCoupon(userDetails.getUsername(), couponId);
        return ResponseEntity.accepted().build();
    }
}
