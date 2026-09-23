// api.js — HTTP 클라이언트, 멱등 키, 요청 ID 표시 (프론트엔드 계획 §5.3, §5.4)
//
// 이 모듈이 백엔드 규약을 모으는 단일 지점이다. 다른 모듈·화면은 여기를 거치지 않고 fetch()를
// 직접 호출하지 않는다 (예외: demo/pg.js — mock-pg는 다른 CORS 규약을 쓴다, §7.2).

// crypto.randomUUID는 secure context(localhost·127.0.0.1·HTTPS)에서만 있다. LAN IP 시연 대비 폴백.
export const uuid = () => crypto.randomUUID?.() ??
  ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g,
    c => (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16));

/** problem+json code가 없으면 HTTP_${status}로 채운다 (Boot 기본 오류 JSON 대비). */
export class ApiError extends Error {
  constructor(body, meta) {
    const safeBody = body ?? {};
    const status = meta.status;
    const code = safeBody.code ?? `HTTP_${status}`;
    super(safeBody.detail ?? safeBody.title ?? `요청이 실패했습니다 (status ${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = safeBody.detail ?? null;
    this.title = safeBody.title ?? null;
    this.requestId = meta.requestId;
    this.body = safeBody;
  }
}

/** 보호 페이지에서 401을 받았을 때 로그인 화면으로 보낸다. 파일명을 명시한다 (§5.1). */
export function redirectToLogin() {
  location.replace('/console/index.html');
}

async function parseBody(res) {
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return { detail: text }; }
}

/** 요청 ID를 window 이벤트로만 알린다 — nav.js를 역참조하지 않기 위해 (순환 import 회피). */
function showRequestId(detail) {
  window.dispatchEvent(new CustomEvent('console:api', { detail }));
}

export async function api(path, { method = 'GET', body, idempotencyKey } = {}) {
  const requestId = uuid();
  const headers = { 'X-Request-Id': requestId };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let res;
  try {
    res = await fetch(path, {
      method, credentials: 'same-origin', headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (networkError) {
    // fetch 자체가 reject됨(오프라인, DNS 실패, 연결 거부 등) — 서버 응답이 없으므로 상태 0으로
    // 구분 가능한 오류를 던진다. 멱등 키는 "응답을 못 받은 경우"이므로 호출부가 유지해야 한다 (§5.4).
    showRequestId({ method, path, status: 0, requestId });
    throw new ApiError(
      { code: 'NETWORK_ERROR', detail: '네트워크 오류로 요청을 보내지 못했습니다.' },
      { status: 0, requestId },
    );
  }

  const meta = { status: res.status, requestId: res.headers.get('X-Request-Id') ?? requestId };

  let data;
  try {
    data = await parseBody(res);
  } catch (readError) {
    // res.text()가 reject됨(연결이 응답 도중 끊김 등) — 서버가 상태 코드는 보냈으므로 그 상태는
    // 살려서 던진다. status 0(=요청 자체가 안 나감)과는 구분되는 오류다.
    showRequestId({ method, path, status: meta.status, requestId: meta.requestId });
    throw new ApiError(
      { code: 'NETWORK_ERROR', detail: '응답 본문을 읽지 못했습니다.' },
      meta,
    );
  }
  showRequestId({ method, path, status: meta.status, requestId: meta.requestId });

  const contentType = res.headers.get('Content-Type') ?? '';
  const isProblem = contentType.includes('application/problem+json');
  // 리소스 바디를 가진 응답만 반환한다: 2xx(202 UNKNOWN·환불 접수 포함)와 결제 SUPERSEDED의 409.
  // problem+json은 상태와 무관하게 오류다 — 품절 409를 성공으로 그리면 안 된다.
  // 409는 그 중에서도 실제 PaymentResponse 바디(JSON이고 status 필드가 문자열)일 때만 리소스로
  // 취급한다 — problem+json이 아닌 다른 409(예: 프록시·Boot 기본 오류 페이지)를 성공으로 오인하지 않는다.
  const isSupersededPaymentBody = res.status === 409
    && !isProblem
    && contentType.includes('application/json')
    && typeof data?.status === 'string';
  if (!isProblem && (res.ok || isSupersededPaymentBody)) return { data, meta };

  // /api/auth/* 의 401(로그인 실패 AUTH_INVALID_CREDENTIALS, /me 미인증)과 로그인 화면에서는
  // 리다이렉트하지 않는다 — 무한 새로고침 방지. 보호 페이지의 미인증 처리는 session.js 역할 가드가 맡는다.
  if (res.status === 401 && !path.startsWith('/api/auth/') && !location.pathname.endsWith('/console/index.html')) {
    redirectToLogin();
  }
  throw new ApiError(data, meta);
}

// --- 멱등 키 — "요청"이 아니라 "사용자 의도" 단위 (§5.4) ---------------------------------

const IDEMPOTENCY_KEY_PREFIX = 'idem:';

export function idempotencyKeyFor(scope) {
  const k = `${IDEMPOTENCY_KEY_PREFIX}${scope}`;
  let v = sessionStorage.getItem(k);
  if (!v) { v = uuid(); sessionStorage.setItem(k, v); }
  return v;
}

export function rotateIdempotencyKey(scope) {
  sessionStorage.removeItem(`${IDEMPOTENCY_KEY_PREFIX}${scope}`);
}

/** idem: 접두 키 전부 삭제 — 로그인 성공·로그아웃 시 session.js가 호출한다. */
export function clearIdempotencyKeys() {
  const toRemove = [];
  for (let i = 0; i < sessionStorage.length; i++) {
    const key = sessionStorage.key(i);
    if (key && key.startsWith(IDEMPOTENCY_KEY_PREFIX)) toRemove.push(key);
  }
  toRemove.forEach((key) => sessionStorage.removeItem(key));
}

// scope 빌더 — nonce를 넣지 않는다. 새로고침해도 같은 scope는 같은 키를 재사용해야
// "커밋됐지만 응답 유실 → 새로고침 → 재주문"을 만들지 않는다 (§5.4).
export const orderCreateScope = (userId, campaignId) => `order-create:${userId}:${campaignId}`;
export const paymentScope = (userId, orderId) => `pay:${userId}:${orderId}`;
export const refundScope = (userId, paymentId) => `refund:${userId}:${paymentId}`;
