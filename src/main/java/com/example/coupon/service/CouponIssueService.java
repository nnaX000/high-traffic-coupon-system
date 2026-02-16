package com.example.coupon.service;

import com.example.coupon.dto.CouponIssueEvent;
import com.example.coupon.entity.Coupon;
import com.example.coupon.entity.CouponIssue;
import com.example.coupon.entity.CouponPolicy;
import com.example.coupon.entity.User;
import com.example.coupon.exception.CouponSoldOutException;
import com.example.coupon.repository.CouponIssueRepository;
import com.example.coupon.repository.CouponPolicyRepository;
import com.example.coupon.repository.CouponRepository;
import com.example.coupon.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponPolicyRepository couponPolicyRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Kafka Consumer: 쿠폰 발급 처리
     * 비관적 락으로 정합성 보장
     * Consumer 개수 제한으로 커넥션 풀 보호
     */
    @KafkaListener(topics = "coupon-issue", concurrency = "10")
    @Transactional
    public void consume(CouponIssueEvent event) {
        // 메트릭: Kafka로부터 소비된 발급 이벤트 수 (Consumer 진입 횟수)
        meterRegistry.counter("coupon_issue_kafka_consumed_total").increment();

        log.info("Processing coupon issue event. couponId: {}, username: {}", 
            event.getCouponId(), event.getUsername());

        try {
            // 쿠폰 조회
            Coupon coupon = couponRepository.findById(event.getCouponId())
                .orElseThrow(() -> new RuntimeException("Coupon not found: " + event.getCouponId()));

            // 비관적 락으로 CouponPolicy 조회
            CouponPolicy policy = couponPolicyRepository.findByIdWithLock(coupon.getPolicy().getId())
                .orElseThrow(() -> new RuntimeException("CouponPolicy not found: " + coupon.getPolicy().getId()));

            // 발급 기간 및 활성화 여부 확인
            LocalDateTime now = LocalDateTime.now();
            if (!policy.isIssuable(now)) {
                log.warn("Coupon not issuable by policy. couponId: {}, now: {}, startAt: {}, endAt: {}, active: {}",
                    event.getCouponId(), now, policy.getStartAt(), policy.getEndAt(), policy.isActive());
                // 비재시도성 비즈니스 케이스 → 조용히 소비 후 종료
                return;
            }

            // 발급 수량 확인
            if (!policy.canIssue()) {
                log.warn("Coupon sold out. couponId: {}, issuedQuantity: {}, totalQuantity: {}", 
                    event.getCouponId(), policy.getIssuedQuantity(), policy.getTotalQuantity());
                // 메트릭: DB 정책 레벨에서 품절로 거부된 발급 이벤트 수
                meterRegistry.counter("coupon_issue_db_sold_out_total").increment();
                throw new CouponSoldOutException();
            }

            // 사용자 조회 (principal = userId)
            User user = userRepository.findByUserId(event.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found: " + event.getUsername()));

            // 중복 발급 확인
            couponIssueRepository.findByUserIdAndCouponId(user.getId(), event.getCouponId())
                .ifPresent(ci -> {
                    log.warn("Coupon already issued. couponId: {}, userId: {}", 
                        event.getCouponId(), user.getId());
                    // 메트릭: Kafka 이벤트 기준 중복 발급 시도 건수
                    meterRegistry.counter("coupon_issue_duplicate_total").increment();
                    throw new RuntimeException("Coupon already issued");
                });

            // 발급 수량 증가
            policy.incrementIssuedQuantity();
            couponPolicyRepository.save(policy);

            // CouponIssue 생성 및 저장
            CouponIssue couponIssue = new CouponIssue(user, coupon, LocalDateTime.now());
            couponIssueRepository.save(couponIssue);

            // 메트릭: DB에 실제 발급(INSERT)까지 완료된 쿠폰 수
            meterRegistry.counter("coupon_issue_db_success_total").increment();

            // 실제 발급 성공 시 Redis count 증가 (현재 발급 수 관리)
            Long count = redisTemplate.opsForValue()
                .increment("coupon:" + event.getCouponId() + ":count");

            // 목적: 누적 발급 횟수 모니터링(취소 여부와 무관한 총 발급 수)용 카운터 증가.
            // 입력/출력: 입력은 couponId, 출력은 Redis에 저장된 issued_total 값.
            // 핵심 로직: 발급 성공 시마다 issued_total 카운터를 단조 증가시킨다.
            Long issuedTotal = redisTemplate.opsForValue()
                .increment("coupon:" + event.getCouponId() + ":issued_total");

            log.info("Coupon issued successfully. couponId: {}, username: {}, count: {}, issuedTotal: {}", 
                event.getCouponId(), event.getUsername(), count, issuedTotal);

        } catch (CouponSoldOutException e) {
            log.error("Failed to issue coupon - sold out. couponId: {}", event.getCouponId());
            return;
        } catch (Exception e) {
            log.error("Failed to issue coupon. couponId: {}, username: {}", 
                event.getCouponId(), event.getUsername(), e);
            throw e;
        }
    }

    /**
     * 쿠폰 취소 처리
     */
    @Transactional
    public void cancelCoupon(Long couponId, String userIdPrincipal) {
        // 사용자 조회 (principal = userId)
        User user = userRepository.findByUserId(userIdPrincipal)
            .orElseThrow(() -> new RuntimeException("User not found: " + userIdPrincipal));

        Long userId = user.getId();
        log.info("Cancelling coupon. couponId: {}, userId: {}", couponId, userId);

        // CouponIssue 조회
        CouponIssue couponIssue = couponIssueRepository
            .findByUserIdAndCouponId(userId, couponId)
            .orElseThrow(() -> new RuntimeException("CouponIssue not found"));

        // 사용된 쿠폰은 취소 불가
        if (couponIssue.isUsed()) {
            throw new RuntimeException("Cannot cancel used coupon");
        }

        // 취소 상태로만 변경 (이력은 보존)
        couponIssue.cancel();

        // Coupon 조회 후 CouponPolicy 발급 수량 감소
        Coupon coupon = couponRepository.findById(couponId)
            .orElseThrow(() -> new RuntimeException("Coupon not found"));
        
        CouponPolicy policy = couponPolicyRepository.findByIdWithLock(coupon.getPolicy().getId())
            .orElseThrow(() -> new RuntimeException("CouponPolicy not found"));
        policy.decrementIssuedQuantity();
        couponPolicyRepository.save(policy);

        // Redis count 감소 (실제 발급 수 감소)
        Long count = redisTemplate.opsForValue()
            .decrement("coupon:" + couponId + ":count");

        // Redis stock 증가 (재고 복구)
        Long stock = redisTemplate.opsForValue()
            .increment(String.format("coupon:%d:stock", couponId));

        log.info("Coupon cancelled successfully. couponId: {}, userId: {}, remaining count: {}, restored stock: {}", 
            couponId, userId, count, stock);
    }
}
