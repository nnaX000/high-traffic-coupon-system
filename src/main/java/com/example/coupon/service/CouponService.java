package com.example.coupon.service;

import com.example.coupon.dto.CouponIssueEvent;
import com.example.coupon.exception.CouponSoldOutException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private static final int LIMIT = 100;

    public void issueCoupon(String username, Long couponId) {
        Long reqCount = redisTemplate.opsForValue().increment("coupon:" + couponId + ":countReq"); // Redis INCR

        if (reqCount > LIMIT) {
            throw new CouponSoldOutException();
        }

        // Kafka 발행
        CouponIssueEvent event = new CouponIssueEvent(couponId, username);
        kafkaTemplate.send("coupon-issue", event);
    }
}
