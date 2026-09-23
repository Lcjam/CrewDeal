// widgets.js — 커스텀 엘리먼트 두 개 (프론트엔드 계획 §5.6)
//   <status-badge domain="payment" value="UNKNOWN">
//   <countdown-timer expires-at="2026-09-23T12:00:00Z">
//
// import하는 즉시 정의된다 — 사용하는 화면에서 `import './widgets.js';`만 하면 된다.

import { statusOf } from './format.js';

class StatusBadge extends HTMLElement {
  static get observedAttributes() {
    return ['domain', 'value'];
  }

  connectedCallback() {
    this.render();
  }

  attributeChangedCallback() {
    this.render();
  }

  render() {
    const domain = this.getAttribute('domain') ?? '';
    const value = this.getAttribute('value') ?? '';
    const [label, color] = statusOf(domain, value);
    this.textContent = label;
    // className을 통째로 덮어쓰지 않는다 — 호출부가 이 엘리먼트에 붙여둔 다른 클래스를 보존한다
    // (Minor 11). 기존 색 modifier만 걷어내고 새 색 modifier로 바꾼다.
    this.classList.add('status-badge');
    Array.from(this.classList)
      .filter((cls) => cls.startsWith('status-badge--'))
      .forEach((cls) => this.classList.remove(cls));
    this.classList.add(`status-badge--${color}`);
  }
}

class CountdownTimer extends HTMLElement {
  static get observedAttributes() {
    return ['expires-at'];
  }

  connectedCallback() {
    this.render();
    this._interval = setInterval(() => this.render(), 1000);
  }

  disconnectedCallback() {
    if (this._interval) {
      clearInterval(this._interval);
      this._interval = null;
    }
  }

  attributeChangedCallback() {
    this.render();
  }

  render() {
    const expiresAt = this.getAttribute('expires-at');
    if (!expiresAt) {
      this.textContent = '-';
      return;
    }
    const remainingMs = new Date(expiresAt).getTime() - Date.now();
    if (Number.isNaN(remainingMs)) {
      this.textContent = '-';
      return;
    }
    if (remainingMs <= 0) {
      this.textContent = '만료';
      this.classList.add('countdown-timer--expired');
      return;
    }
    this.classList.remove('countdown-timer--expired');
    const totalSeconds = Math.floor(remainingMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    this.textContent = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
}

if (!customElements.get('status-badge')) customElements.define('status-badge', StatusBadge);
if (!customElements.get('countdown-timer')) customElements.define('countdown-timer', CountdownTimer);
