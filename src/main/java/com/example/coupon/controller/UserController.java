package com.example.coupon.controller;

import com.example.coupon.dto.JwtToken;
import com.example.coupon.dto.LoginRequest;
import com.example.coupon.dto.SignupRequest;
import com.example.coupon.jwt.JwtTokenProvider;
import com.example.coupon.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        userService.signup(
                request.getUserId(),
                request.getUsername(),
                request.getPassword(),
                request.getEmail()
        );

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 로그인
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        Authentication authentication =
                authenticationManager.authenticate(//여기서 UserDetailsService 호출
                        new UsernamePasswordAuthenticationToken(
                                req.getUsername(), req.getPassword()
                        )
                );

        JwtToken token = jwtTokenProvider.generateToken(authentication);
        return ResponseEntity.ok(token);
    }
}