package com.example.coupon.dto;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssueEvent {
    private Long couponId;
    private String username;
}
