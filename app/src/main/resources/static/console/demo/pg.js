// pg.js — mock-pg 전용 fetch 래퍼 (프론트엔드 계획 §7.2, §7.3)
//
// api.js는 모든 요청에 X-Request-Id를 붙인다. mock-pg의 CORS 설정(WebMvcConfigurer,
// /mock-pg/test/** 한정)은 허용 헤더로 Content-Type만 연다 — X-Request-Id를 보내면 프리플라이트가
// 거부된다. 그래서 mock-pg 호출은 api()를 거치지 않고 이 모듈 전용으로 만든다 (§7.2 지시).
//
// mock-pg에는 "현재 모드 조회" API가 없다(TestControlController:17-19 참고 — POST /failure-mode의
// 응답으로만 그 순간 적용한 설정을 안다). 그래서 이 모듈은 마지막으로 이 탭이 적용에 성공한
// FailureModeSettings만 기억한다 — 다른 탭이 그 사이 모드를 바꿨을 수 있다는 뜻이고, 배지 문구로
// 그 한계를 명시한다 (demo.js).

const STORAGE_KEY = 'demo:mockPgUrl';

function defaultBaseUrl() {
  return `${location.protocol}//${location.hostname}:8081`;
}

function normalize(url) {
  return url.trim().replace(/\/+$/, '');
}

/** 저장된 주소가 있으면 그것을, 없으면 이 탭의 호스트명 기준 기본값을 돌려준다. */
export function getMockPgUrl() {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored ? normalize(stored) : defaultBaseUrl();
}

/** 사용자 입력을 저장한다(끝의 '/' 정리). 이후 getMockPgUrl()이 이 값을 돌려준다. */
export function setMockPgUrl(url) {
  const normalized = normalize(url);
  if (normalized) localStorage.setItem(STORAGE_KEY, normalized);
  else localStorage.removeItem(STORAGE_KEY);
  return normalized || defaultBaseUrl();
}

export function defaultMockPgUrl() {
  return defaultBaseUrl();
}

/** mock-pg 호출 오류. kind: 'network'(연결 자체 실패 — CORS 거부도 브라우저에선 TypeError로 보여
 * 이 갈래로 온다) | 'http'(4xx/5xx, 상태·본문 있음). */
export class MockPgError extends Error {
  constructor(message, { status, body, kind }) {
    super(message);
    this.name = 'MockPgError';
    this.status = status;
    this.body = body ?? null;
    this.kind = kind;
  }
}

let lastFailureModeSettings = null;
const listeners = new Set();

/** 마지막으로 이 탭이 적용에 성공한 FailureModeSettings 응답 (없으면 null). */
export function lastKnownFailureMode() {
  return lastFailureModeSettings;
}

/** 모드가 바뀔 때(성공적으로 적용됐을 때)마다 호출된다. 구독 해제 함수를 돌려준다. */
export function onFailureModeChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function notifyFailureModeChange(settings) {
  lastFailureModeSettings = settings;
  listeners.forEach((listener) => {
    try {
      listener(settings);
    } catch {
      // 구독자 쪽 오류로 이 모듈의 상태 갱신을 막지 않는다.
    }
  });
}

async function call(path, { method = 'GET', body } = {}) {
  const url = `${getMockPgUrl()}${path}`;
  let res;
  try {
    res = await fetch(url, {
      method,
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // fetch 자체가 reject됨 — 오프라인, 잘못된 주소·포트, 또는 CORS 거부(브라우저에서는 TypeError로만
    // 보이고 원인을 구분할 수 없다). 셋을 한 문구로 안내한다.
    throw new MockPgError('mock-pg에 연결하지 못했습니다 (주소·포트·CORS 확인)', { status: 0, kind: 'network' });
  }
  const text = await res.text().catch(() => '');
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { detail: text };
    }
  }
  if (!res.ok) {
    const detail = data?.detail ?? data?.message ?? text ?? '';
    throw new MockPgError(`mock-pg 요청이 실패했습니다 (status ${res.status})${detail ? ` — ${detail}` : ''}`,
      { status: res.status, body: data, kind: 'http' });
  }
  return data;
}

/** 요청은 통째로 교체된다 — 생략 필드는 mock-pg 기본값으로 돌아간다 (§7.3). */
export async function setFailureMode(settings) {
  const data = await call('/mock-pg/test/failure-mode', { method: 'POST', body: settings });
  notifyFailureModeChange(data);
  return data;
}

export function resetNormal() {
  return setFailureMode({ mode: 'NORMAL' });
}

/** 페이지 이탈 시 최선 노력으로 NORMAL을 보낸다 — 응답을 기다리지 않는다 (§7.4 "실행 중 페이지를
 * 떠나려 하면 pagehide에서 keepalive로 NORMAL 복구"). */
export function resetNormalBestEffort() {
  try {
    // fetch()는 await하지 않는다(호출부가 pagehide 안에서 동기로 끝나야 한다) — 그래서 실패는
    // try/catch가 아니라 .catch()로 잡아야 한다. 안 그러면 처리되지 않은 프라미스 거부가 된다.
    fetch(`${getMockPgUrl()}/mock-pg/test/failure-mode`, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'NORMAL' }),
      keepalive: true,
    }).catch(() => {
      // 최선 노력 — 실패해도 무시한다.
    });
  } catch {
    // 최선 노력 — 실패해도 무시한다.
  }
}

/**
 * 대상을 항상 지정한다. 대상이 둘 다 비면 호출하지 않고 오류를 던진다 — 바디 없이 부르면 mock-pg가
 * 보류 큐 전체를 발사한다(§7.3, PendingWebhookQueue:32-45).
 */
export function replayWebhooks({ providerPaymentId, merchantPaymentId } = {}) {
  if (!providerPaymentId && !merchantPaymentId) {
    throw new Error('웹훅 재발사 대상(providerPaymentId 또는 merchantPaymentId)이 필요합니다 — 비우면 보류 웹훅 전체가 발사됩니다.');
  }
  return call('/mock-pg/test/webhooks/replay', { method: 'POST', body: { providerPaymentId, merchantPaymentId } });
}

/** 주입 거래는 mock-pg 메모리에 남고 삭제 API가 없다 (§7.3). */
export function injectTransaction(request) {
  return call('/mock-pg/test/transactions', { method: 'POST', body: request });
}

export function stats() {
  return call('/mock-pg/test/stats');
}
