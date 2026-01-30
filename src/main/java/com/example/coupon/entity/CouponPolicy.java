package com.example.coupon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
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

    public boolean isIssuable(LocalDateTime now) {
        return active && !now.isBefore(startAt) && !now.isAfter(endAt);
    }
}
