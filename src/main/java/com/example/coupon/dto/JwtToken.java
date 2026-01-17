package com.example.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Builder
@AllArgsConstructor
@Data
public class JwtToken {
    private String grantType; // Bearer 인증 방식

    @ToString.Exclude
    private String accessToken;

    @ToString.Exclude
    private String refreshToken;
}
