// poll.js — 폴링 (프론트엔드 계획 §5.5)
//
// setTimeout 체인(응답이 늦어도 요청이 겹치지 않는다) + 완만한 백오프(첫 호출은 즉시, 그다음 간격은
// intervalMs에서 시작해 x1.5, 상한 maxIntervalMs). 문서가 숨겨져 있으면 호출만 건너뛴다. pagehide에서 정지, pageshow에서
// event.persisted면 재시작(bfcache 복귀) — 단, pagehide가 "실행 중이던" 폴링을 멈춘 경우에만
// 재시작한다. until() 완료나 타임아웃으로 이미 끝난 폴링, stop()으로 명시적으로 멈춘 폴링은
// pageshow가 되살리지 않는다 — 타임아웃 이후의 유일한 재개 경로는 "계속 지켜보기" 버튼(restart())이다.

/**
 * @param {object} opts
 * @param {() => Promise<any>} opts.fn - 폴링할 조회 함수
 * @param {(result: any) => boolean} opts.until - true면 폴링을 멈춘다 (성공 종료)
 * @param {number} [opts.intervalMs=2000]
 * @param {number} [opts.maxIntervalMs=8000]
 * @param {number} [opts.timeoutMs=60000]
 * @param {(result: any, error: Error|null) => void} [opts.onTick] - 매 호출 결과(또는 오류) 콜백.
 *   fn()이 실패하면 error에 그 오류가, until()이 던지면 error에 그 오류가 담긴다(오류 tick으로 취급).
 * @param {() => void} [opts.onTimeout] - 타임아웃 시 콜백. 자동 재시작하지 않는다.
 * @returns {{ stop: () => void, restart: () => void }}
 */
export function poll({ fn, until, intervalMs = 2000, maxIntervalMs = 8000, timeoutMs = 60000, onTick, onTimeout }) {
  // 'running'   — 정상 진행 중, setTimeout 체인이 살아있다.
  // 'suspended' — pagehide가 "실행 중이던" 폴링을 멈췄다. pageshow(persisted)만 재개할 수 있다.
  // 'stopped'   — until() 성공, 타임아웃, 또는 stop() 호출로 끝났다. 다시는 자동으로 살아나지 않는다.
  let state = 'running';
  let timer = null;
  let generation = 0;
  let currentInterval = intervalMs;
  let startedAt = Date.now();
  let listenersAttached = false;

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function schedule(delay, gen) {
    clearTimer();
    timer = setTimeout(() => tick(gen), delay);
  }

  async function tick(gen) {
    timer = null;
    // restart()/stop()/suspend가 이 tick이 예약된 뒤에 일어났다면 gen이 더 이상 현재 generation이
    // 아니다 — 낡은 체인이므로 아무 것도 하지 않는다.
    if (gen !== generation || state !== 'running') return;

    if (document.visibilityState !== 'hidden') {
      let result;
      let tickError = null;
      try {
        result = await fn();
      } catch (err) {
        tickError = err;
      }

      // fn()이 await 도는 동안 restart()/stop()/pagehide가 일어났을 수 있다(M1-a). generation이
      // 바뀌었거나 더 이상 running이 아니면 이 tick은 낡은 체인이다 — onTick도 호출하지 않고
      // 다음 스케줄도 잡지 않은 채 조용히 빠진다.
      if (gen !== generation || state !== 'running') return;

      let done = false;
      if (!tickError) {
        try {
          done = Boolean(until(result));
        } catch (untilError) {
          // until()이 던져도 폴링을 죽이지 않는다 — 오류 tick으로 취급하고 계속한다 (M2).
          console.error('[poll] until() threw — treated as an error tick, polling continues', untilError);
          tickError = untilError;
          done = false;
        }
      }

      try {
        if (onTick) onTick(result, tickError);
      } catch (onTickError) {
        // onTick()이 던져도 폴링을 죽이지 않는다 (M2).
        console.error('[poll] onTick() threw — polling continues', onTickError);
      }

      if (gen !== generation || state !== 'running') return;

      if (!tickError && done) {
        finish();
        return;
      }
    }

    if (gen !== generation || state !== 'running') return;

    if (Date.now() - startedAt >= timeoutMs) {
      finish();
      if (onTimeout) onTimeout();
      return;
    }

    schedule(currentInterval, gen);
    currentInterval = Math.min(currentInterval * 1.5, maxIntervalMs);
  }

  /** until() 성공 또는 타임아웃 — 둘 다 "다시는 자동으로 안 살아나는" 종결 상태다 (M1-b). */
  function finish() {
    state = 'stopped';
    generation += 1;
    clearTimer();
    detachListeners();
  }

  function stop() {
    state = 'stopped';
    generation += 1;
    clearTimer();
    detachListeners();
  }

  function restart() {
    state = 'running';
    generation += 1;
    currentInterval = intervalMs;
    startedAt = Date.now();
    attachListeners();
    schedule(0, generation);
  }

  function handlePageHide() {
    if (state !== 'running') return;
    state = 'suspended';
    generation += 1; // 진행 중이던 예약/체인을 무효화한다
    clearTimer();
    // 리스너는 유지한다 — pageshow가 와야 재개 여부를 판단할 수 있다 (detach는 finish/stop에서만).
  }

  function handlePageShow(event) {
    // pagehide가 "실행 중이던" 폴링을 멈춘 경우에만 재개한다. until() 완료·stop()·타임아웃으로
    // 끝난 폴링은 이 리스너가 이미 detach되어 있으므로 애초에 호출되지 않지만, 이중 방어로 상태도 확인한다.
    if (state === 'suspended' && event.persisted) {
      restart();
    }
  }

  function attachListeners() {
    if (listenersAttached) return;
    listenersAttached = true;
    window.addEventListener('pagehide', handlePageHide);
    window.addEventListener('pageshow', handlePageShow);
  }

  function detachListeners() {
    if (!listenersAttached) return;
    listenersAttached = false;
    window.removeEventListener('pagehide', handlePageHide);
    window.removeEventListener('pageshow', handlePageShow);
  }

  attachListeners();
  schedule(0, generation);

  return { stop, restart };
}

/**
 * 타임아웃 시 컨테이너에 "계속 지켜보기" 버튼을 그린다. onTimeout에서 이 함수를 호출하면 된다:
 *   poll({ ..., onTimeout: () => renderContinueWatching(el, handle) })
 */
export function renderContinueWatching(container, handle, label = '계속 지켜보기') {
  container.innerHTML = '';
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-secondary';
  button.textContent = label;
  button.addEventListener('click', () => {
    handle.restart();
    container.innerHTML = '';
  });
  container.appendChild(button);
}
