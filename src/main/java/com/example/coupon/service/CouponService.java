package com.example.coupon.service;

import com.example.coupon.dto.CouponIssueEvent;
import com.example.coupon.entity.Coupon;
import com.example.coupon.exception.CouponSoldOutException;
import com.example.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;

    private static final String COUPON_LIMIT_KEY_PREFIX = "coupon:%d:limit";
    private static final String COUPON_COUNT_REQ_KEY_PREFIX = "coupon:%d:countReq";
    private static final String COUPON_COUNT_KEY_PREFIX = "coupon:%d:count";

    /**
     * 쿠폰 발급 요청
     * - 쿠폰별 정책(totalQuantity)을 기반으로 Limit을 결정
     * - Limit은 Redis에 캐싱
     * - Redis countReq로 선차단 후 Kafka로 비동기 처리
     */
    public void issueCoupon(String username, Long couponId) {
        int limit = getCouponLimit(couponId);

        // countReq 증가 (선차감) - INCR 결과로 제한 초과 여부 판단
        String countReqKey = String.format(COUPON_COUNT_REQ_KEY_PREFIX, couponId);
        Long reqCount = redisTemplate.opsForValue().increment(countReqKey);

        if (reqCount != null && reqCount > limit) {
            log.warn("Coupon request limit exceeded. couponId: {}, reqCount: {}, limit: {}", couponId, reqCount, limit);
            throw new CouponSoldOutException();
        }

        // Kafka 발행 (비동기)
        CouponIssueEvent event = new CouponIssueEvent(couponId, username);
        kafkaTemplate.send("coupon-issue", event);

        log.debug("Coupon issue event sent to Kafka. couponId: {}, username: {}", couponId, username);
    }

    /**
     * 쿠폰별 발급 한도(limit) 조회
     * - 우선 Redis에서 조회
     * - 없으면 DB에서 Coupon → CouponPolicy.totalQuantity 조회 후 Redis에 캐싱
     */
    private int getCouponLimit(Long couponId) {
        String limitKey = String.format(COUPON_LIMIT_KEY_PREFIX, couponId);

        String cachedLimit = redisTemplate.opsForValue().get(limitKey);
        if (cachedLimit != null) {
            try {
                return Integer.parseInt(cachedLimit);
            } catch (NumberFormatException e) {
                log.warn("Invalid limit value in Redis. key: {}, value: {}", limitKey, cachedLimit);
                // 아래에서 DB에서 다시 읽어 리셋
            }
        }

        // DB에서 Coupon 조회 후 Policy의 totalQuantity 사용
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new IllegalArgumentException("Coupon not found. id=" + couponId));

        int limit = coupon.getPolicy().getTotalQuantity();

        // Redis에 캐싱 (TTL은 필요에 따라 추가 가능)
        redisTemplate.opsForValue().set(limitKey, String.valueOf(limit));

        return limit;
    }

    /**
     * 쿠폰 취소
     * DB에서 삭제하고 Redis count만 감소 (countReq는 그대로)
     */
    @Transactional
    public void cancelCoupon(Long couponId, Long userId) {
        // DB에서 쿠폰 발급 내역 삭제
        // (실제 구현은 CouponIssueService에서 처리)

        // Redis count만 감소 (재고 복구)
        String countKey = String.format(COUPON_COUNT_KEY_PREFIX, couponId);
        Long count = redisTemplate.opsForValue().decrement(countKey);

        log.info("Coupon cancelled. couponId: {}, userId: {}, remaining count: {}",
            couponId, userId, count);

        // countReq는 건드리지 않음 (과거 요청 기록이므로)
    }
}
