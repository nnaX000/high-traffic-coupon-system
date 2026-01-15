package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Builder
@AllArgsConstructor
@Data
public class JwtToken {
    private String grantType; // Bearer 인증 방식
    private String accessToken;
    private String refreshToken;
}
