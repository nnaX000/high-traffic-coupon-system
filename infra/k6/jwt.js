/**
 * JWT HS256 서명 (Java JwtTokenProvider와 동일한 키/포맷).
 * - 키: Base64(UTF-8(secret)) 문자열을 HMAC 키로 사용 (Keys.hmacShaKeyFor(Base64.encode(secret.getBytes()))와 동일).
 * - payload: sub = userId(loginId), auth = "ROLE_USER" 필수.
 */
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

/**
 * HS256 JWT 생성
 * @param {string} userId - sub 클레임 (loginId/userId)
 * @param {string} secret - jwt.secret와 동일한 값
 * @param {number} expSec - 만료 시각 (초 단위 Unix timestamp). 생략 시 현재+24시간
 * @returns {string} JWT 문자열
 */
export function createAccessToken(userId, secret, expSec) {
  const keyForHmac = encoding.b64encode(secret, 'std');
  const header = { alg: 'HS256', typ: 'JWT' };
  const nowSec = Math.floor(Date.now() / 1000);
  const payload = {
    sub: userId,
    auth: 'ROLE_USER',
    exp: expSec || nowSec + 86400,
  };
  const encodedHeader = encoding.b64encode(JSON.stringify(header), 'rawurl');
  const encodedPayload = encoding.b64encode(JSON.stringify(payload), 'rawurl');
  const message = `${encodedHeader}.${encodedPayload}`;
  const hasher = crypto.createHMAC('sha256', keyForHmac);
  hasher.update(message);
  const signature = hasher.digest('base64rawurl');
  return `${message}.${signature}`;
}
