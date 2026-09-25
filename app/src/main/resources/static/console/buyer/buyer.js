// buyer.js — 구매자 화면 공통 부트스트랩 (프론트엔드 계획 §6 "구매자")
//
// 모든 구매자 화면은 bootBuyer()로 시작한다: BUYER 역할 가드 → 공용 헤더.
// 멱등 키 scope에 userId가 들어가므로(§5.4) 호출부는 반환된 user.id를 그대로 쓴다.

import { requireRole } from '../common/session.js';
import { mountNav } from '../common/nav.js';
import '../common/widgets.js';

/**
 * 구매자 화면 공통 시작. 역할이 맞지 않거나 미인증이면 리다이렉트되고 null을 돌려준다.
 * 페이지는 <div id="console-nav"></div>를 가진다.
 * @returns {Promise<{id:number,email:string,displayName:string,role:string}|null>}
 */
export async function bootBuyer() {
  const user = await requireRole('BUYER');
  if (!user) return null;
  mountNav(user);
  return user;
}
