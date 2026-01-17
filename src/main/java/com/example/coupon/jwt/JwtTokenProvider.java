package com.example.coupon.jwt;

import com.example.coupon.dao.RedisDao;
import com.example.coupon.dto.JwtToken;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.security.Key;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;

//JWT를 생성하고 검증하는 등의 핵심 기능을 제공하는 클래스
//@Configuration
@Slf4j
@Configuration
public class JwtTokenProvider {
    private final Key key;
    private final UserDetailsService userDetailsService;
    private final RedisDao redisDao; // RefreshToken 저장을 위해 Redis 사용

    private static final String GRANT_TYPE = "Bearer";

    @Value("${jwt.access-token.expire-time}") // 1000 * 60 * 60 * 24 = 1일
    private long ACCESS_TOKEN_EXPIRE_TIME;

    @Value("${jwt.refresh-token.expire-time}") // 1000 * 60 * 60 * 24 * 3 = 3일
    private long REFRESH_TOKEN_EXPIRE_TIME;

    // application.properties에서 secret 값 가져와서 secretKey 사용하기
    public JwtTokenProvider(@Value("${jwt.secret}") String secretKey,
                            UserDetailsService userDetailsService,
                            RedisDao redisDao) {
        byte[] keyBytes = Base64.getEncoder().encode(secretKey.getBytes());
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.userDetailsService = userDetailsService;
        this.redisDao = redisDao;
    }

    //authentication으로부터 access토큰, refresh토큰 생성
    public JwtToken generateToken(Authentication authentication) {
        // 권한 가져오기
        // JWT 토큰의 claims에 포함되어 사용자의 권한 정보를 저장하는데 사용됨
        String authorities = authentication.getAuthorities().stream() // Authentication 객체에서 사용자 권한 목록 가져오기
                .map(GrantedAuthority::getAuthority) // 각 GrantedAuthority 객체에서 권한 문자열만 추출하기
                .collect(Collectors.joining(",")); // 추출된 권한 문자열들을 쉼표로 구분하여 하나의 문자열로 결합하기

        long now = (new Date()).getTime();
        String username = authentication.getName();

        // AccessToken 생성
        Date accessTokenExpire = new Date(now + ACCESS_TOKEN_EXPIRE_TIME);
        String accessToken = generateAccessToken(username, authorities, accessTokenExpire);

        // RefreshToken 생성
        Date refreshTokenExpire = new Date(now + REFRESH_TOKEN_EXPIRE_TIME);
        String refreshToken = generateRefreshToken(username, refreshTokenExpire);

        // Redis에 RefreshToken 넣기
        // "REFRESH_TOKEN_EXPIRE_TIME"만큼 시간이 지나면 삭제됨
        redisDao.setValues(username, refreshToken, Duration.ofMillis(REFRESH_TOKEN_EXPIRE_TIME));

        return JwtToken.builder()
                .grantType(GRANT_TYPE) // "Bearer"
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }


    //    AccessToken에는 인증된 사용자 정보와 권한 정보, 토큰 만료 시간을 포함하고 있다.
    //    RefreshToken은 AccessToken 재발급 용도로만 사용하므로, 토큰 만료 시간만 포함하고 있다.
    private String generateAccessToken(String username, String authorities, Date expireDate) {
        return Jwts.builder()
                .setSubject(username) // 토큰 제목 (사용자 이름)
                .claim("auth", authorities) // 권한 정보 (커스텀 클레임)
                .setExpiration(expireDate) // 토큰 만료 시간
                .signWith(key, SignatureAlgorithm.HS256) // 지정된 키와 알고리즘으로 서명
                .compact(); // 최종 JWT 문자열 생성 (header.payload.signature 구조);
    }

    private String generateRefreshToken(String username, Date expireDate) {
        return Jwts.builder()
                .setSubject(username)
                .setExpiration(expireDate)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Authentication은 “JWT를 Spring Security가 이해할 수 있게 변환한 로그인 정보 객체”
    public Authentication getAuthentication(String accessToken) {
        // JWT 토큰 복호화
        Claims claims = parseClaims(accessToken);
        if (claims.get("auth") == null) {
            throw new RuntimeException("권한 정보가 없는 토큰입니다.");
        }

        // 클레임에서 권한 정보 가져오기
        Collection<? extends GrantedAuthority> authorities = Arrays.stream(claims.get("auth").toString().split(","))
                .map(SimpleGrantedAuthority::new) // SimpleGrantedAuthority 객체들의 컬렉션으로 변환
                .toList();


        // 애플리케이션에서 사용되는 사용자의 정보를 표현
        // UserDetails 객체를 만들어서 Authentication return
        // UserDetails: interface, User: UserDetails를 구현한 클래스
        UserDetails principal = new User(claims.getSubject(), "", authorities); // 파라미터: 사용자 식별자, credentials, 권한 목록
        return new UsernamePasswordAuthenticationToken(principal, "", authorities);
    }

    // JWT 토큰 복호화
    // payload = Claims
    private Claims parseClaims(String accessToken) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(accessToken) // JWT 토큰 검증과 파싱을 모두 수행함
                    .getBody();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    // 토큰 정보 검증, 이 토큰이 진짜인지만 확인
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token); //JWT 서명을 검증하고, 만료 확인하고, payload(Claims)를 추출

            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT Token", e);
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT Token", e);
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT Token", e);
        } catch (IllegalArgumentException e) {
            log.info("JWT claims string is empty", e);
        }
        return false;
    }

    // RefreshToken 검증
    public boolean validateRefreshToken(String token) {
        // 기본적인 JWT 검증
        if (!validateToken(token)) return false;

        try {
            // token에서 username 추출하기
            String username = getUserNameFromToken(token);
            // Redis에 저장된 RefreshToken과 비교하기
            String redisToken = (String) redisDao.getValues(username);
            return token.equals(redisToken); // refresh 토큰을 이름(키) : 리프레시 토큰(밸류)로 저장해서 가능한 것.
        } catch (Exception e) {
            log.info("RefreshToken Validation Failed", e);
            return false;
        }
    }

    // 토큰에서 username 추출
    public String getUserNameFromToken(String token) {
        try {
            // 토큰 파싱해서 클레임 얻기
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 사용자 이름(subject) 반환
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            // 토큰이 만료되어도 클레임 내용을 가져올 수 있음
            return e.getClaims().getSubject();
        }
    }

    // RefreshToken 삭제
    public void deleteRefreshToken(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        // 로그아웃 시 Redis에서 RefreshToken 삭제
        redisDao.deleteValues(username);
    }
}
