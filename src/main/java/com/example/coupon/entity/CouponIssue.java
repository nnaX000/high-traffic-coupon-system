package com.example.coupon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "coupon_issue",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "coupon_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    public CouponIssue(User user, Coupon coupon, LocalDateTime issuedAt) {
        this.user = user;
        this.coupon = coupon;
        this.issuedAt = issuedAt;
        this.used = false;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 발급 받은 유저
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 발급된 쿠폰
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    // 발급 시간
    @Column(nullable = false)
    private LocalDateTime issuedAt;

    // 사용 여부
    @Column(nullable = false)
    private boolean used = false;

    // 취소 여부 (true면 발급 이력은 있지만 사용자가 취소한 상태)
    @Column(nullable = false)
    private boolean canceled = false;

    // 목적: 쿠폰을 사용 처리한다.
    // 입력/출력: 입력 없음, 내부 필드 used를 true로 변경한다.
    // 핵심 로직: 현재 엔티티의 used 플래그를 true로 설정한다.
    public void use() {
        this.used = true;
    }

    // 목적: 쿠폰 발급을 취소 상태로 표시한다.
    // 입력/출력: 입력 없음, 내부 필드 canceled를 true로 변경한다.
    // 핵심 로직: 현재 엔티티의 canceled 플래그를 true로 설정한다.
    public void cancel() {
        this.canceled = true;
    }
}
