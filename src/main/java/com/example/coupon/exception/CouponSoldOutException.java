package com.example.coupon.exception;

public class CouponSoldOutException extends RuntimeException {
    public CouponSoldOutException() {
        super("Coupon is sold out");
    }
}