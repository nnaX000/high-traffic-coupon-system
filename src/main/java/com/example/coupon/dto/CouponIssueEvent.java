package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CouponIssueEvent {
    private Long couponId;
    private String username;
}
