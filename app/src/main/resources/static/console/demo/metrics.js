// metrics.js — 앱 카운터·mock-pg stats 스냅숏/증분 (프론트엔드 계획 §7.3)
//
// 둘 다 누적값이다. 시나리오는 시작 시 스냅숏을 뜨고, 증거 단계에서 diffCounters()로 Δ만 비교한다.
// 앱 카운터·mock-pg stats 둘 다 로그인 역할과 무관하게 보인다(§7.3) — 이 모듈은 role을 신경 쓰지 않는다.

import { api } from '../common/api.js';
import * as pg from './pg.js';

/** /actuator/metrics/{name} 대상 — payment.unknown은 태그 합산(§7.3), 나머지는 태그 없음. */
export const APP_COUNTER_NAMES = [
  'inventory.sold.out',
  'payment.unknown',
  'payment.duplicate.prevented',
  'webhook.duplicate',
  'ledger.unbalanced',
];

export const APP_COUNTER_LABELS = {
  'inventory.sold.out': '재고 소진 (inventory.sold.out)',
  'payment.unknown': '결제 확정대기 (payment.unknown)',
  'payment.duplicate.prevented': '중복 결제 차단 (payment.duplicate.prevented)',
  'webhook.duplicate': '웹훅 중복 (webhook.duplicate)',
  'ledger.unbalanced': '원장 불균형 (ledger.unbalanced)',
};

export const PG_STAT_LABELS = {
  confirmRequestCount: 'PG 승인 요청 (confirmRequestCount)',
  webhookSentCount: '웹훅 발송 (webhookSentCount)',
  webhookPendingCount: '웹훅 보류 (webhookPendingCount)',
  refundRequestCount: '환불 요청 (refundRequestCount)',
  refundExecutedCount: '환불 실행 (refundExecutedCount)',
};

/** 단일 앱 카운터 값. 실패하면 null — 호출부가 "-"로 표시하고 스냅숏/Δ 계산에서도 null로 흘러간다. */
export async function fetchAppCounter(name) {
  try {
    const { data } = await api(`/actuator/metrics/${encodeURIComponent(name)}`);
    const measurement = (data.measurements ?? []).find((m) => m.statistic === 'COUNT');
    return measurement ? Number(measurement.value) : 0;
  } catch {
    return null;
  }
}

export async function snapshotAppCounters() {
  const entries = await Promise.all(APP_COUNTER_NAMES.map(async (name) => [name, await fetchAppCounter(name)]));
  return Object.fromEntries(entries);
}

export async function snapshotPgStats() {
  try {
    return await pg.stats();
  } catch {
    return null; // mock-pg 미연결 — 호출부가 "-"로 표시한다.
  }
}

/** 앱 카운터 5개 + mock-pg stats 5개를 한 번에 스냅숏한다. */
export async function snapshotCounters() {
  const [app, pgStats] = await Promise.all([snapshotAppCounters(), snapshotPgStats()]);
  return { app, pg: pgStats };
}

function diffMap(start, current) {
  if (!start || !current) return null;
  const out = {};
  for (const key of Object.keys(current)) {
    const c = current[key];
    const s = start[key];
    out[key] = typeof c === 'number' && typeof s === 'number' ? c - s : null;
  }
  return out;
}

/** start·current는 snapshotCounters()의 결과. 둘 중 하나라도 없으면 null. */
export function diffCounters(start, current) {
  if (!start || !current) return null;
  return { app: diffMap(start.app, current.app), pg: diffMap(start.pg, current.pg) };
}
