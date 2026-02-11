package com.example.coupon.repository;

import com.example.coupon.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
    
    @Query("SELECT ci FROM CouponIssue ci WHERE ci.user.id = :userId AND ci.coupon.id = :couponId")
    Optional<CouponIssue> findByUserIdAndCouponId(@Param("userId") Long userId, @Param("couponId") Long couponId);
    
    @Modifying
    @Query("DELETE FROM CouponIssue ci WHERE ci.coupon.id = :couponId AND ci.user.id = :userId")
    void deleteByCouponIdAndUserId(@Param("couponId") Long couponId, @Param("userId") Long userId);
    
    @Query("SELECT COUNT(ci) FROM CouponIssue ci WHERE ci.coupon.id = :couponId")
    long countByCouponId(@Param("couponId") Long couponId);
}
