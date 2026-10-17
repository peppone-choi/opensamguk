import { describe, expect, it } from 'vitest';
import { observeSubmit, sanitizeEvidence, TurnDiagnostics } from '../e2e/qa160-evidence';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const diagnostic = () => {
  const value = new TurnDiagnostics();
  value.enter('S16_SUBMIT_RESPONSE', 'command-post');
  return value;
};

describe('S16 submit boundary witnesses', () => {
  it('registers the response before clicking and preserves the response identity', async () => {
    const d = diagnostic();
    const order: string[] = [];
    const response = { status: 202 };
    const actual = await observeSubmit(d, () => { order.push('response'); return Promise.resolve(response); },
      async () => { order.push('click'); });
    expect(order).toEqual(['response', 'click']);
    expect(actual).toBe(response);
    expect(d.probe).toEqual({ submitPhase: 'RESPONSE_RECEIVED', submitClickCompleted: true, submitResponseObserved: true });
    expect(d.stage).toBe('S16_SUBMIT_RESPONSE');
    expect(d.httpStatus).toBeNull();
  });

  it('distinguishes a pending click from a pending response without adding timers', async () => {
    const d = diagnostic();
    const click = deferred<void>();
    const response = deferred<number>();
    const pending = observeSubmit(d, () => response.promise, () => click.promise);
    expect(d.probe.submitPhase).toBe('CLICK_PENDING');
    expect(d.probe.submitClickCompleted).toBe(false);
    click.resolve();
    await Promise.resolve();
    expect(d.probe.submitPhase).toBe('RESPONSE_PENDING');
    expect(d.probe.submitClickCompleted).toBe(true);
    expect(d.probe.submitResponseObserved).toBe(false);
    response.resolve(202);
    await expect(pending).resolves.toBe(202);
  });

  it('does not mislabel a response received before click completion', async () => {
    const d = diagnostic();
    const click = deferred<void>();
    const pending = observeSubmit(d, () => Promise.resolve(202), () => click.promise);
    await Promise.resolve();
    expect(d.probe).toEqual({ submitPhase: 'CLICK_PENDING', submitClickCompleted: false, submitResponseObserved: true });
    click.resolve();
    await expect(pending).resolves.toBe(202);
  });

  it('keeps the original click failure even when the response waiter also rejects', async () => {
    const d = diagnostic();
    const original = new Error('private click failure');
    const pending = observeSubmit(d, () => Promise.reject(new Error('private response failure')),
      async () => { throw original; });
    await expect(pending).rejects.toBe(original);
    expect(d.probe.submitPhase).toBe('CLICK_PENDING');
    expect(d.probe.submitClickCompleted).toBe(false);
    expect(d.failure(original).errorClass).toBe('INTERNAL');
  });

  it('keeps the original response failure after a completed click', async () => {
    const d = diagnostic();
    const original = new Error('private response failure');
    await expect(observeSubmit(d, () => Promise.reject(original), async () => {})).rejects.toBe(original);
    expect(d.probe.submitPhase).toBe('RESPONSE_PENDING');
    expect(d.probe.submitClickCompleted).toBe(true);
    expect(d.probe.submitResponseObserved).toBe(false);
  });

  it('records synchronous response-registration failure without attempting the click', async () => {
    const d = diagnostic();
    let clicked = false;
    const original = new Error('private registration failure');
    await expect(observeSubmit(d, () => { throw original; }, async () => { clicked = true; })).rejects.toBe(original);
    expect(clicked).toBe(false);
    expect(d.probe.submitPhase).toBe('REGISTER_RESPONSE');
  });

  it.each(['REGISTER_RESPONSE', 'CLICK_PENDING', 'RESPONSE_PENDING', 'RESPONSE_RECEIVED'])
    ('allows only fixed submit enum %s and booleans on FAIL', submitPhase => {
      const value = sanitizeEvidence({ status: 'FAIL', probe: { submitPhase, submitClickCompleted: false,
        submitResponseObserved: true, message: 'private', requestId: 'private' } }, 'turn-reservation');
      expect(value).toEqual({ status: 'FAIL', probe: { submitPhase, submitClickCompleted: false,
        submitResponseObserved: true }, sanitizerDropped: 2 });
    });

  it('drops hostile text in every new position and keeps FAIL witnesses out of PASS', () => {
    const probe = { submitPhase: 'Bearer private', submitClickCompleted: 'password-private', submitResponseObserved: 'sam_access=private' };
    const value = sanitizeEvidence({ status: 'FAIL', probe }, 'turn-reservation');
    expect(value).toEqual({ status: 'FAIL', probe: {}, sanitizerDropped: 3 });
    expect(sanitizeEvidence({ status: 'PASS', probe: { submitPhase: 'CLICK_PENDING' } }, 'turn-reservation'))
      .toEqual({ status: 'PASS', sanitizerDropped: 1 });
  });
});
