import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, createApi } from '../../src/main/resources/static/assets/api.js';

function reply(status, body, headers = {}) {
  const h = new Headers(headers);
  if (body !== undefined && !h.has('content-type')) h.set('content-type', 'application/json');
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: h });
}

function setup(respond, extra = {}) {
  const calls = [];
  let unauthorised = 0;
  const api = createApi({
    getToken: () => 'tok123',
    onUnauthorised: () => { unauthorised += 1; },
    fetchImpl: async (path, init) => { calls.push({ path, init }); return respond(path, init); },
    ...extra,
  });
  return { api, calls, unauthorised: () => unauthorised };
}

test('every call carries the bearer token, asks for JSON, and sends no cookies, cache or referrer', async () => {
  const { api, calls } = setup(() => reply(200, []));
  await api.listPayees();
  const { path, init } = calls[0];
  assert.equal(path, '/v1/payees');
  assert.equal(init.method, 'GET');
  assert.equal(init.headers.Authorization, 'Bearer tok123');
  assert.match(init.headers.Accept, /application\/json/);
  assert.equal(init.credentials, 'omit');
  assert.equal(init.cache, 'no-store');
  assert.equal(init.referrerPolicy, 'no-referrer');
  assert.equal(init.body, undefined);
  assert.equal(init.headers['Content-Type'], undefined);
});

test('a payment carries its Idempotency-Key and a JSON body', async () => {
  const { api, calls } = setup(() => reply(201, { id: 'p1' }));
  const out = await api.initiatePayment('key-12345678', { payeeId: 'x', amountPence: 2500, reference: 'DINNER' });
  assert.equal(out.status, 201);
  assert.equal(out.replayed, false);
  const { init } = calls[0];
  assert.equal(init.method, 'POST');
  assert.equal(init.headers['Idempotency-Key'], 'key-12345678');
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(init.body), { payeeId: 'x', amountPence: 2500, reference: 'DINNER' });
});

test('a replay is reported as one', async () => {
  const { api } = setup(() => reply(200, { id: 'p1' }, { 'Idempotent-Replayed': 'true' }));
  assert.equal((await api.initiatePayment('key-12345678', {})).replayed, true);
});

test('ids are encoded into the path', async () => {
  const { api, calls } = setup(() => reply(200, {}));
  await api.getPayment('a/b?c');
  assert.equal(calls[0].path, '/v1/payments/a%2Fb%3Fc');
});

test('confirming sends the warning code that was shown', async () => {
  const { api, calls } = setup(() => reply(200, {}));
  await api.confirmPayment('p1', 'SCAM_LANGUAGE');
  assert.equal(calls[0].path, '/v1/payments/p1/confirm');
  assert.deepEqual(JSON.parse(calls[0].init.body), { acknowledgedWarning: 'SCAM_LANGUAGE' });
});

test('a 401 signs the person out and throws', async () => {
  const { api, unauthorised } = setup(() => reply(401, undefined));
  await assert.rejects(api.listPayees(), (e) => e instanceof ApiError && e.status === 401);
  assert.equal(unauthorised(), 1);
});

test('a problem detail becomes an ApiError with the server message', async () => {
  const { api } = setup(() => reply(400, { title: 'Bad Request', status: 400, detail: 'an account number is eight digits' },
    { 'content-type': 'application/problem+json' }));
  await assert.rejects(api.registerPayee({}), (e) => e.status === 400 && e.message === 'an account number is eight digits'
    && e.problem.detail === 'an account number is eight digits');
});

test('429 carries Retry-After', async () => {
  const { api } = setup(() => reply(429, { detail: 'slow down' }, { 'Retry-After': '12', 'content-type': 'application/problem+json' }));
  await assert.rejects(api.initiatePayment('k', {}), (e) => e.status === 429 && e.retryAfter === 12);
});

test('a bad Retry-After is ignored', async () => {
  const { api } = setup(() => reply(429, {}, { 'Retry-After': 'soon' }));
  await assert.rejects(api.initiatePayment('k', {}), (e) => e.retryAfter === null);
});

test('an error with no JSON body still throws a sensible ApiError', async () => {
  const { api } = setup(() => reply(502, undefined));
  await assert.rejects(api.listPayees(), (e) => e.status === 502 && e.message === 'HTTP 502');
});

test('no response (offline) is status 0', async () => {
  const { api } = setup(() => { throw new TypeError('Failed to fetch'); });
  await assert.rejects(api.listPayees(), (e) => e.status === 0 && e.isNetwork);
});

test('a request that takes too long is abandoned as no response', async () => {
  const { api } = setup((path, init) => new Promise((_, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  }), { timeoutMs: 20 });
  await assert.rejects(api.listPayees(), (e) => e.isNetwork);
});
