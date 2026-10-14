import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as fmt from '../../src/main/resources/static/assets/format.js';

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const jwt = (claims) => `${b64({ alg: 'RS256' })}.${b64(claims)}.c2ln`;

test('parsePounds reads whole pounds and pence without floating point', () => {
  const cases = [
    ['25', 2500], ['25.00', 2500], ['25.5', 2550], ['25.05', 2505], ['0.01', 1], ['0.10', 10], ['£3', 300],
    ['£ 3.20', 320], ['1,200', 120000], ['1,200.50', 120050], [' 7 ', 700], ['00025', 2500], ['25,000.00', 2500000],
    // 0.1 + 0.2 style traps: these must be exact
    ['0.29', 29], ['1.15', 115], ['4.35', 435], ['19.99', 1999],
  ];
  for (const [text, pence] of cases) assert.equal(fmt.parsePounds(text), pence, text);
});

test('parsePounds refuses anything that is not a plain amount', () => {
  for (const text of ['', ' ', 'abc', '.5', '5.', '1.234', '1,20.00', '1,2000', '-5', '+5', '1e3', '£', '£-1', '25p', '2 5', '1,200,00',
    '99999999999999999999']) {
    assert.equal(fmt.parsePounds(text), null, JSON.stringify(text));
  }
  assert.equal(fmt.parsePounds(undefined), null);
  assert.equal(fmt.parsePounds(null), null);
});

test('formatPence', () => {
  assert.equal(fmt.formatPence(0), '£0.00');
  assert.equal(fmt.formatPence(1), '£0.01');
  assert.equal(fmt.formatPence(2500), '£25.00');
  assert.equal(fmt.formatPence(120050), '£1,200.50');
  assert.equal(fmt.formatPence(2500000), '£25,000.00');
  assert.equal(fmt.formatPence(40000000), '£400,000.00');
});

test('parsePounds and formatPence agree', () => {
  for (const pence of [1, 9, 10, 99, 100, 101, 12345, 2500000]) {
    assert.equal(fmt.parsePounds(fmt.formatPence(pence)), pence);
  }
});

test('sort codes: six digits, with or without hyphens or spaces', () => {
  assert.equal(fmt.parseSortCode('12-34-56'), '123456');
  assert.equal(fmt.parseSortCode('123456'), '123456');
  assert.equal(fmt.parseSortCode(' 12 34 56 '), '123456');
  for (const bad of ['', '12345', '1234567', '12-34-5a', '12–34–56', 'abcdef']) assert.equal(fmt.parseSortCode(bad), null, bad);
  assert.equal(fmt.formatSortCode('123456'), '12-34-56');
});

test('account numbers: eight digits, spaces allowed', () => {
  assert.equal(fmt.parseAccountNumber('12345678'), '12345678');
  assert.equal(fmt.parseAccountNumber('1234 5678'), '12345678');
  for (const bad of ['', '1234567', '123456789', '1234-5678', 'abcdefgh']) assert.equal(fmt.parseAccountNumber(bad), null, bad);
});

test('payee names: 1 to 70 characters with a letter', () => {
  assert.ok(fmt.isValidPayeeName('John Smith'));
  assert.ok(fmt.isValidPayeeName("Sara O'Connor"));
  assert.ok(fmt.isValidPayeeName('Zoë Müller'));
  assert.ok(fmt.isValidPayeeName('7 Eleven Ltd'));
  assert.ok(fmt.isValidPayeeName('a'.repeat(70)));
  assert.ok(!fmt.isValidPayeeName('a'.repeat(71)));
  assert.ok(!fmt.isValidPayeeName(''));
  assert.ok(!fmt.isValidPayeeName('   '));
  assert.ok(!fmt.isValidPayeeName('12345'));
});

test('references follow the API rule', () => {
  for (const ok of ['DINNER', 'INVOICE 77', 'a.b/c&d-e', 'x'.repeat(18), ' padded ']) assert.ok(fmt.isValidReference(ok), ok);
  for (const bad of ['', '   ', 'x'.repeat(19), 'pay £5', 'a_b', '<b>', 'café']) assert.ok(!fmt.isValidReference(bad), bad);
});

test('lastFour reads the masked account number', () => {
  assert.equal(fmt.lastFour('****5678'), '5678');
  assert.equal(fmt.lastFour(''), '');
  assert.equal(fmt.lastFour(undefined), '');
});

test('readToken reads who is signed in and what they may do, without trusting it', () => {
  const t = fmt.readToken(jwt({ sub: 'alice', scope: 'payments reviews', exp: 4102444800 }));
  assert.deepEqual(t, { sub: 'alice', scopes: ['payments', 'reviews'], exp: 4102444800 });
  assert.deepEqual(fmt.readToken(jwt({ sub: 'bo', scp: ['payments'] })).scopes, ['payments']);
  assert.deepEqual(fmt.readToken(jwt({ sub: 'bo', scp: 'payments reviews' })).scopes, ['payments', 'reviews']);
  assert.deepEqual(fmt.readToken(jwt({ sub: 'bo' })).scopes, []);
  assert.equal(fmt.readToken(jwt({ sub: 'bo' })).exp, null);
  assert.equal(fmt.readToken(`  ${jwt({ sub: 'bo', scope: 'payments' })}\n`).sub, 'bo');
  assert.equal(fmt.readToken(jwt({ sub: 'José', scope: 'payments' })).sub, 'José');
});

test('readToken refuses text that is not a three-part token with a subject', () => {
  for (const bad of ['', 'abc', 'a.b', 'a.b.c.d', 'a.b c.d', jwt({ scope: 'payments' }), jwt({ sub: '' }), jwt({ sub: 5 }),
    `${b64({})}.${'!!!'}.x`, `${b64({})}.${Buffer.from('not json').toString('base64url')}.x`]) {
    assert.equal(fmt.readToken(bad), null, bad);
  }
  assert.equal(fmt.readToken(undefined), null);
});

test('idempotency keys satisfy the API pattern, with and without crypto.randomUUID', () => {
  assert.match(fmt.newIdempotencyKey(), fmt.IDEMPOTENCY_KEY_PATTERN);
  const noUuid = { getRandomValues: (a) => globalThis.crypto.getRandomValues(a) };
  const keys = new Set();
  for (let i = 0; i < 50; i += 1) {
    const k = fmt.newIdempotencyKey(noUuid);
    assert.match(k, fmt.IDEMPOTENCY_KEY_PATTERN);
    assert.match(k, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    keys.add(k);
  }
  assert.equal(keys.size, 50);
});

test('fieldForProblem points a server message at the field it is about', () => {
  assert.equal(fmt.fieldForProblem('an amount is between 1p and £25000.00'), 'amount');
  assert.equal(fmt.fieldForProblem('a reference is 1 to 18 letters, digits, spaces or the characters - . / &'), 'reference');
  assert.equal(fmt.fieldForProblem('a sort code is six digits, for example 12-34-56'), 'sort-code');
  assert.equal(fmt.fieldForProblem('an account number is eight digits'), 'account-number');
  assert.equal(fmt.fieldForProblem('a payee name is 1 to 70 characters and includes a letter'), 'payee-name');
  assert.equal(fmt.fieldForProblem('no such payee'), null);
  assert.equal(fmt.fieldForProblem(undefined), null);
});
