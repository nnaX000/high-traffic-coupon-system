/**
 * k6 쿠폰 발급 "버스트 5초" 정합성 테스트 (로그인 API 없이 JWT 직접 생성)
 *
 * 목표:
 * - Flash Sale 상황(짧은 시간에 몰림)을 재현
 * - 요청마다 전역 유니크 userId 사용 -> 유저당 1회 제한에 절대 안 걸리게
 * - Kafka 비동기 처리이므로, k6 응답만으로 정합성 판정하지 말고
 *   테스트 종료 후 coupon_issue(DB)에서 "성공 발급 건수 = 500"인지 확인해야 함.
 *
 * JWT:
 * - sub = userId(loginId)
 * - auth = ROLE_USER
 * - HS256 (서버 loadtest 프로필의 jwt.secret과 동일)
 *
 * 실행 예시:
 *   SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
 *
 *   BASE_URL="http://localhost:8080" \
 *   COUPON_ID="1" \
 *   JWT_SECRET="loadtest-secret-please-change-32bytes-minimum-123456" \
 *   RATE="3000" \
 *   DURATION="5s" \
 *   USER_OFFSET="1" \
 *   USER_PREFIX="user_" \
 *   PRE_VUS="200" \
 *   MAX_VUS="5000" \
 *   k6 run infra/k6/coupon-issue-burst-5s.js
 *
 * 2천만 이하 보장 체크:
 * - 총 유니크 유저 수 ≈ RATE * durationSeconds
 * - 최대 userNo ≈ USER_OFFSET + RATE * durationSeconds
 * - 예: RATE=3000, DURATION=5s -> 15000명 수준 (2천만 훨씬 아래)
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { createAccessToken } from './jwt.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '1';
const JWT_SECRET = __ENV.JWT_SECRET || 'loadtest-secret-please-change-32bytes-minimum-123456';

// 버스트 유입률(초당 요청 수)과 지속시간
const RATE = parseInt(__ENV.RATE || '3000', 10);
const DURATION = __ENV.DURATION || '5s';

// userId 형식: user_1, user_2, ...
const USER_PREFIX = __ENV.USER_PREFIX || 'user_';
const USER_OFFSET = parseInt(__ENV.USER_OFFSET || '1', 10);

// 429를 "기대 가능한 제한 응답"으로 허용할지
const ACCEPT_429 = (__ENV.ACCEPT_429 || 'true').toLowerCase() === 'true';

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // arrival-rate는 시스템이 느리면 더 많은 VU를 투입해 rate를 맞추려 함
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || '200', 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '5000', 10),
      exec: 'issueCoupon',
    },
  },
  thresholds: {
    // 정합성 테스트에서는 5xx가 없어야 함. (409/410/429는 설계에 따라 정상일 수 있음)
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<30000'],
  },
};

export function issueCoupon() {
  // 시나리오 전체에서 유니크하게 증가하는 전역 iteration 번호
  // - VU가 몇 개든 "요청마다" 겹치지 않음
  const globalIter = exec.scenario.iterationInTest;

  // 요청마다 유니크 userId (2천만 이하 여부는 RATE*durationSeconds로 계산 가능)
  const userNo = USER_OFFSET + globalIter;
  const userId = `${USER_PREFIX}${userNo}`;

  // JWT 즉석 생성 (로그인 API 호출 없음)
  const token = createAccessToken(userId, JWT_SECRET);

  const res = http.post(
    `${BASE_URL}/api/coupons/${COUPON_ID}/issue`,
    null,
    {
      headers: {
        Authorization: `Bearer ${token}`,
      },
      tags: { scenario: 'burst' },
    }
  );

  // 참고:
  // - 재고 500개라면, 초반 일부만 202(accepted) 나오고 이후에는 품절/제한 응답이 나올 수 있음
  // - Kafka 비동기라 202는 "접수"일 뿐, 최종 발급 성공은 DB(coupon_issue)로 판정
  check(res, {
    'accepted or expected rejection': (r) => {
      if (r.status === 202) return true; // 접수 성공
      if (r.status === 409) return true; // sold out / conflict (설계에 따라)
      if (r.status === 410) return true; // sold out / gone (설계에 따라)
      if (ACCEPT_429 && r.status === 429) return true; // rate-limited (설정에 따라)
      return false;
    },
    'no 5xx': (r) => r.status < 500,
  });
}