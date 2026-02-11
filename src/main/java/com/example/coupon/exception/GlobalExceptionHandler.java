package com.example.coupon.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<?> handleSoldOut(CouponSoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "status", "SOLD_OUT",
                        "message", e.getMessage()
                ));
    }
}
