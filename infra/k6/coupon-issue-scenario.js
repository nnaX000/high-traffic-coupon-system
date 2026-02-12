// 목적: 고트래픽 쿠폰 발급 API에 대한 기본 부하/시나리오 테스트를 수행한다.
// 입력: k6 옵션(env, vus, duration 등), 대상 URL (BASE_URL), 쿠폰 정책 ID 등.
// 출력: 요청 성공률/지연 시간/에러 비율 등의 메트릭을 k6 표준 출력 및 Prometheus(설정 시)로 노출한다.
// 핵심 로직: 일정 VU와 RPS로 /api/coupons/{policyId}/issue 엔드포인트를 반복 호출한다.

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: __ENV.VUS ? Number(__ENV.VUS) : 50,
  duration: __ENV.DURATION || '1m',
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POLICY_ID = __ENV.POLICY_ID || '1';

export default function () {
  const url = `${BASE_URL}/api/coupons/${POLICY_ID}/issue`;

  const res = http.post(url, null, {
    headers: {
      'Content-Type': 'application/json',
    },
  });

  check(res, {
    'status is 200 or 202': (r) => r.status === 200 || r.status === 202,
  });

  // 짧은 think time 을 줘서 완전한 폭주 대신 현실적인 트래픽 패턴을 흉내냄
  sleep(0.1);
}

