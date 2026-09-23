// html.js — 안전한 렌더링 헬퍼 (프론트엔드 계획 §5.6)
//
// 서버·사용자 문자열은 전부 이 모듈의 escapeHtml()을 거쳐야 한다. html`` 템플릿 태그는
// 보간값을 기본적으로 escapeHtml()로 처리하고, 신뢰된 마크업만 raw()로 예외 처리한다.
// html``의 결과 자체도 "신뢰됨"으로 표시되므로 raw() 없이 다른 html`` 안에 그대로 중첩할 수 있다
// (리뷰 Minor 5) — 중첩할 때 이중 이스케이프가 나지 않는다.

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** & < > " ' 5자를 치환한다. null/undefined는 빈 문자열로. */
export function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[&<>"']/g, (ch) => ESCAPE_MAP[ch]);
}

const SAFE = Symbol('html.safe');

/**
 * "신뢰됨" 마커가 붙은 문자열 래퍼. toString()이 원본 문자열을 돌려주므로
 * `.map(...).join('')`, `el.innerHTML = 결과`, `insertAdjacentHTML(pos, 결과)`,
 * `String(결과)`, 템플릿 리터럴 보간(`` `${결과}` ``) 모두 별도 처리 없이 그대로 동작한다.
 */
function markSafe(str) {
  return {
    [SAFE]: true,
    value: str,
    toString() {
      return this.value;
    },
  };
}

function isSafe(value) {
  return Boolean(value) && typeof value === 'object' && value[SAFE] === true;
}

/** 신뢰된 마크업만 이 함수를 거쳐 html`` 안에서 이스케이프 없이 삽입한다. */
export function raw(value) {
  return markSafe(String(value ?? ''));
}

function stringifyValue(value) {
  if (isSafe(value)) return value.value;
  if (Array.isArray(value)) return value.map(stringifyValue).join('');
  return escapeHtml(value);
}

/**
 * 템플릿 리터럴 태그. 보간값은 기본적으로 escapeHtml()을 거친다.
 * 신뢰된 HTML을 그대로 넣으려면 raw()로 감싼다: html`<div>${raw(trustedMarkup)}</div>`
 * 이 함수 자신의 결과(html`...`)를 다른 html`` 안에 보간해도 이스케이프되지 않는다 — 이미 안전하다고
 * 표시되어 있기 때문이다. 반환값은 문자열이 아니라 toString()을 갖는 객체이므로, 문자열이 필요한
 * 자리(innerHTML, insertAdjacentHTML, join, 템플릿 보간)에서는 자동으로 문자열로 바뀐다.
 */
export function html(strings, ...values) {
  let result = strings[0];
  for (let i = 0; i < values.length; i++) {
    result += stringifyValue(values[i]);
    result += strings[i + 1];
  }
  return markSafe(result);
}

/**
 * 쿼리스트링에서 안전한 양의 정수 ID만 뽑는다. 형식이 다르면 null.
 * (§5.6 "payment.html?orderId=../../campaigns/5/orders" 같은 경로 조작 방지)
 */
export function idParam(name, searchParams) {
  const params = searchParams ?? new URLSearchParams(location.search);
  const rawValue = params.get(name);
  if (rawValue === null) return null;
  if (!/^[0-9]+$/.test(rawValue)) return null;
  const id = Number(rawValue);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}
