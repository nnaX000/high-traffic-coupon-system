package com.example.coupon.dto;

import lombok.Getter;

@Getter
public class SignupRequest {
    private String userId;
    private String username;
    private String password;
    private String email;
}
