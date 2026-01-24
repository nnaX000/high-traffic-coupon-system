package com.example.coupon.jwt;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import java.io.IOException;

@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends GenericFilter {
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        // Request Header에서 JWT 토큰 추출
        String accessToken = resolveToken((HttpServletRequest) request);

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestURI = httpRequest.getRequestURI();

        if (requestURI.equals("/api/login") || requestURI.equals("/api/signup")) {
            chain.doFilter(request, response);
            return;
        }

        // accessToken 유효성 검사하기
        if (accessToken != null) {
            if (jwtTokenProvider.validateToken(accessToken)) {
                // 토큰이 유효할 경우, 토큰에서 Authentication 객체를 가지고 와서 SecurityContext에 저장함
                Authentication authentication = jwtTokenProvider.getAuthentication(accessToken); // 토큰에 있는 정보 꺼내기
                SecurityContextHolder.getContext().setAuthentication(authentication); // 현재 실행 중인 스레드에 인증 정보를 저장
            } else {
                // 토큰이 유효하지 않은 경우, 더 이상의 필터 처리 하지 않음
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 인증 정보 부족
                return;
            }
        }
        chain.doFilter(request, response); // 다음 필터로 요청 전달
    }

    // Request Header에서 JWT 토큰 추출
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7); // "Bearer " 이후만 넘기기
        }
        return null;
    }
}
