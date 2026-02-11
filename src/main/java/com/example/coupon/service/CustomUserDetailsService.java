package com.example.coupon.service;

import com.example.coupon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String loginId) {
        // loginId는 userId로 사용
        com.example.coupon.entity.User user =
                userRepository.findByUserId(loginId)
                        .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return org.springframework.security.core.userdetails.User.builder()
                // Security principal로 userId 사용
                .username(user.getUserId())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}
