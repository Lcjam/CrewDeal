// supplier.js — 공급사 화면 공통 부트스트랩 (프론트엔드 계획 §6 "공급사")
//
// 모든 공급사 화면은 bootSupplier()로 시작한다: SUPPLIER 역할 가드 → 공용 헤더.
// buyer.js(bootBuyer)와 같은 패턴 — 화면마다 requireRole·mountNav를 중복해서 쓰지 않는다.

import { requireRole } from '../common/session.js';
import { mountNav } from '../common/nav.js';
import '../common/widgets.js';

/**
 * 공급사 화면 공통 시작. 역할이 맞지 않거나 미인증이면 리다이렉트되고 null을 돌려준다.
 * 페이지는 <div id="console-nav"></div>를 가진다.
 * @returns {Promise<{id:number,email:string,displayName:string,role:string}|null>}
 */
export async function bootSupplier() {
  const user = await requireRole('SUPPLIER');
  if (!user) return null;
  mountNav(user);
  return user;
}
