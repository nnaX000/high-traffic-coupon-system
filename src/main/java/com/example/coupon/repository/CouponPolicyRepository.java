package com.example.coupon.repository;

import com.example.coupon.entity.CouponPolicy;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cp FROM CouponPolicy cp WHERE cp.id = :id")
    Optional<CouponPolicy> findByIdWithLock(@Param("id") Long id);
}
