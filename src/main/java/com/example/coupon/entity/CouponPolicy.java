package com.example.coupon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupon_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 총 발급 수량
    @Column(nullable = false)
    private int totalQuantity;

    // 발급 시작 시간
    @Column(nullable = false)
    private LocalDateTime startAt;

    // 발급 종료 시간
    @Column(nullable = false)
    private LocalDateTime endAt;

    // 활성화 여부
    @Column(nullable = false)
    private boolean active;

    // 현재 발급 수량
    @Column(nullable = false)
    private int issuedQuantity = 0;

    public boolean isIssuable(LocalDateTime now) {
        return active && !now.isBefore(startAt) && !now.isAfter(endAt);
    }

    public boolean canIssue() {
        return issuedQuantity < totalQuantity;
    }

    public void incrementIssuedQuantity() {
        if (!canIssue()) {
            throw new com.example.coupon.exception.CouponSoldOutException();
        }
        this.issuedQuantity++;
    }

    public void decrementIssuedQuantity() {
        if (this.issuedQuantity > 0) {
            this.issuedQuantity--;
        }
    }
}
