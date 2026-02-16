package com.example.coupon.service;

import com.example.coupon.dto.CouponIssueEvent;
import com.example.coupon.entity.Coupon;
import com.example.coupon.exception.CouponSoldOutException;
import com.example.coupon.repository.CouponRepository;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final KafkaTemplate<String, CouponIssueEvent> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final MeterRegistry meterRegistry;

    private static final String COUPON_LIMIT_KEY_PREFIX = "coupon:%d:limit";
    // 남은 재고(선차단용) 카운터
    private static final String COUPON_STOCK_KEY_PREFIX = "coupon:%d:stock";
    private static final String COUPON_COUNT_KEY_PREFIX = "coupon:%d:count";

    /**
     * 쿠폰 발급 요청
     * - 쿠폰별 정책(totalQuantity)을 기반으로 Redis stock(남은 재고) 초기화
     * - Redis stock(DECR)을 통해 선차단 후 Kafka로 비동기 처리
     */
    public void issueCoupon(String username, Long couponId) {
        // 메트릭: 쿠폰 발급 API 진입 횟수 (요청 수 모니터링)
        meterRegistry.counter("coupon_issue_requests_total").increment();

        // 1. 남은 재고(stock) 키 계산
        String stockKey = String.format(COUPON_STOCK_KEY_PREFIX, couponId);

        // 2. stock 키가 없을 때만 totalQuantity로 초기화 (SETNX로 한 번만 세팅, 덮어쓰기 방지)
        if (Boolean.FALSE.equals(redisTemplate.hasKey(stockKey))) {
            int limit = getCouponLimit(couponId);
            redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(limit));
        }

        // 3. 남은 재고에서 1 감소 (원자적)
        Long remain = redisTemplate.opsForValue().decrement(stockKey);

        // 재고가 0 미만으로 내려갔으면 즉시 복구 후 SOLD OUT
        if (remain != null && remain < 0) {
            // 메트릭: Redis 선차감 단계에서 품절로 막힌 요청 수
            meterRegistry.counter("coupon_issue_redis_sold_out_total").increment();
            // 보정: 잘못 깎인 만큼 되돌리기
            redisTemplate.opsForValue().increment(stockKey);
            log.warn("Coupon sold out at Redis stock gate. couponId: {}, remain: {}", couponId, remain);
            throw new CouponSoldOutException();
        }

        // 4. Kafka 발행 (비동기) - key를 couponId로 사용하여 쿠폰별 순서/파티셔닝 보장
        CouponIssueEvent event = new CouponIssueEvent(couponId, username);
        kafkaTemplate.send("coupon-issue", String.valueOf(couponId), event);

        // 메트릭: Kafka에 정상 발행된 쿠폰 발급 이벤트 수
        meterRegistry.counter("coupon_issue_kafka_sent_total").increment();

        log.debug("Coupon issue event sent to Kafka. topic: {}, key(couponId): {}, username: {}, remainStock: {}",
            "coupon-issue", couponId, username, remain);
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
}
