// The page must say what the API says. These tests read the API description (api/openapi.yaml) and the Java source,
// and fail when the page's text, patterns or demo data fall out of step with them.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { parse } from 'yaml';
import * as copy from '../../src/main/resources/static/assets/copy.js';
import * as fmt from '../../src/main/resources/static/assets/format.js';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const staticDir = join(repo, 'src', 'main', 'resources', 'static');
const java = join(repo, 'src', 'main', 'java', 'io', 'github', 'michaelsamuel2005', 'payguard');
const read = (...p) => readFileSync(join(...p), 'utf8');
const api = parse(read(repo, 'api', 'openapi.yaml'));
const schema = (name) => api.components.schemas[name].properties;
const keys = (obj) => Object.keys(obj).sort();
const sorted = (arr) => [...arr].sort();

test('the page has words for every warning code the API can send', () => {
  assert.deepEqual(keys(copy.WARNING_HEADINGS), sorted(schema('Warning').code.enum));
});

test('the page has words for every signal, payment status, name check and audit action', () => {
  assert.deepEqual(keys(copy.SIGNAL_LABELS), sorted(schema('Payment').signals.items.enum));
  assert.deepEqual(keys(copy.PAYMENT_STATUS), sorted(schema('Payment').status.enum));
  assert.deepEqual(keys(copy.NAME_CHECK), sorted(schema('Payee').nameCheck.enum));
  assert.deepEqual(keys(copy.AUDIT_ACTIONS), sorted(schema('AuditEntry').action.enum));
});

test('the API enums match the Java enums they describe', () => {
  const enumNames = (file) => [...read(java, 'domain', file).matchAll(/^\s+([A-Z_]+)\s*(?:\(|,|;)/gm)].map((m) => m[1]);
  assert.deepEqual(sorted(enumNames('WarningCode.java')), sorted(schema('Warning').code.enum));
  assert.deepEqual(sorted(enumNames('Signal.java')), sorted(schema('Payment').signals.items.enum));
  assert.deepEqual(sorted(enumNames('PaymentStatus.java')), sorted(schema('Payment').status.enum));
});

test('the reference and idempotency-key rules are the API\'s own', () => {
  assert.equal(fmt.REFERENCE_PATTERN.source, schema('InitiatePayment').reference.pattern);
  const key = api.paths['/v1/payments'].post.parameters.find((p) => p.name === 'Idempotency-Key');
  assert.equal(fmt.IDEMPOTENCY_KEY_PATTERN.source, key.schema.pattern);
  assert.equal(schema('RegisterPayee').name.maxLength, 70);
  assert.equal(schema('ReviewDecision').reason.maxLength, 500);
});

test('every call the page makes is an operation in the API description', () => {
  const src = read(staticDir, 'assets', 'api.js');
  const used = [...src.matchAll(/call\('(GET|POST)',\s*[`'](\/v1\/[^`'?]*)/g)]
    .map((m) => `${m[1]} ${m[2].replace(/\$\{id\([a-zA-Z]+\)\}/g, '{id}')}`);
  const described = Object.entries(api.paths).flatMap(([path, ops]) =>
    Object.keys(ops).filter((m) => ['get', 'post'].includes(m)).map((m) => `${m.toUpperCase()} ${path}`));
  // The page has no screen that looks up one payee by id (it lists them), so that one operation is not called.
  const notUsed = ['GET /v1/payees/{id}'];
  assert.equal(used.length, 10);
  for (const call of used) assert.ok(described.includes(call), `the page calls ${call}, which the API does not describe`);
  for (const op of described.filter((o) => !notUsed.includes(o))) {
    assert.ok(used.includes(op), `the API describes ${op}, which the page never calls`);
  }
});

test('the demo page lists exactly the accounts the simulated directory knows', () => {
  const directory = read(java, 'adapters', 'directory', 'SimulatedAccountNameDirectory.java');
  const accounts = [...directory.matchAll(/"(\d{6})"\s*\+\s*"(\d{8})",\s*"([^"]+)"/g)]
    .map((m) => [m[3], `${m[1].slice(0, 2)}-${m[1].slice(2, 4)}-${m[1].slice(4)}`, m[2]]);
  assert.equal(accounts.length, 7);
  const about = read(staticDir, 'about.html');
  const table = about.slice(about.indexOf('<caption>Fictional accounts</caption>'));
  const rows = [...table.slice(0, table.indexOf('</table>')).matchAll(/<tr><td>([^<]+)<\/td><td>([^<]+)<\/td><td>([^<]+)<\/td><\/tr>/g)]
    .map((m) => [m[1].replace(/&#39;/g, "'").toUpperCase(), m[2], m[3]]);
  assert.deepEqual(rows.sort(), accounts.sort());
});

test('the demo page explains the points and thresholds the code uses', () => {
  const signals = [...read(java, 'domain', 'Signal.java').matchAll(/([A-Z_]+)\((\d+),\s*"([^"]+)"\)/g)];
  assert.equal(signals.length, 7);
  const about = read(staticDir, 'about.html');
  const table = about.slice(about.indexOf('<caption>Points for each warning sign</caption>'));
  const rows = [...table.slice(0, table.indexOf('</table>')).matchAll(/<tr><td>([^<]+)<\/td><td>(\d+)<\/td><\/tr>/g)]
    .map((m) => Number(m[2]));
  assert.deepEqual(rows.sort(), signals.map((m) => Number(m[2])).sort());
  const policy = read(java, 'domain', 'ScamPolicy.java').match(/DEFAULT = new ScamPolicy\((\d+), (\d+),/);
  const [warnAt, holdAt] = [Number(policy[1]), Number(policy[2])];
  assert.equal(warnAt, 2);
  assert.equal(holdAt, 4);
  assert.ok(about.includes(`0 or ${warnAt - 1} points: the payment is sent. ${warnAt} or ${holdAt - 1} points: `
    + `the customer sees a warning and must confirm. ${holdAt} points or more: the payment is held`));
});

test('the demo scenarios use the rules as written: new payee, scam words and the large-payment floor', () => {
  const policy = read(java, 'domain', 'ScamPolicy.java');
  assert.ok(/Money\.ofPounds\(1_000\), 5, Money\.ofPounds\(250\)/.test(policy), 'the £1,500 example assumes a £1,000 floor');
  const rules = read(java, 'domain', 'ScamRules.java');
  for (const phrase of ['SAFE ACCOUNT']) assert.ok(rules.includes(`"${phrase}"`), phrase);
});

test('the content security policy is strict', () => {
  const config = read(java, 'config', 'SecurityConfig.java');
  const literal = config.match(/CONTENT_SECURITY_POLICY\s*=\s*((?:"[^"]*"\s*\+?\s*)+);/)[1];
  const csp = [...literal.matchAll(/"([^"]*)"/g)].map((m) => m[1]).join('');
  const directives = Object.fromEntries(csp.split(';').map((d) => d.trim().split(/\s+/)).map(([n, ...v]) => [n, v]));
  assert.deepEqual(directives['default-src'], ["'none'"]);
  assert.deepEqual(directives['script-src'], ["'self'"]);
  assert.deepEqual(directives['style-src'], ["'self'"]);
  assert.deepEqual(directives['frame-ancestors'], ["'none'"]);
  assert.deepEqual(directives['base-uri'], ["'none'"]);
  assert.ok(!/unsafe-|\*|https?:/.test(csp), 'no unsafe-inline, unsafe-eval, wildcard or other origin');
});

// The browser enforces the policy at run time. These source checks fail earlier, with a better message.
const htmlFiles = readdirSync(staticDir).filter((f) => f.endsWith('.html'));
const assetFiles = readdirSync(join(staticDir, 'assets'));

test('no page has inline script, inline style, event handlers or another origin', () => {
  assert.deepEqual(htmlFiles.sort(), ['about.html', 'accessibility.html', 'index.html']);
  for (const file of htmlFiles) {
    const html = read(staticDir, file);
    assert.ok(!/<script(?![^>]*\ssrc=)[^>]*>/i.test(html), `${file}: inline <script>`);
    assert.ok(!/<style/i.test(html), `${file}: <style> element`);
    assert.ok(!/\sstyle\s*=/i.test(html), `${file}: style attribute`);
    assert.ok(!/\son[a-z]+\s*=/i.test(html), `${file}: inline event handler`);
    assert.ok(!/(src|href)\s*=\s*["']\s*(https?:)?\/\//i.test(html), `${file}: another origin`);
    assert.ok(!/javascript:/i.test(html), `${file}: javascript: URL`);
  }
});

test('every page declares its language, title, viewport and exactly one main landmark', () => {
  for (const file of htmlFiles) {
    const html = read(staticDir, file);
    assert.ok(/<html lang="en-GB">/.test(html), `${file}: lang`);
    assert.ok(/<title>[^<]{3,}<\/title>/.test(html), `${file}: title`);
    assert.ok(/<meta name="viewport" content="width=device-width, initial-scale=1">/.test(html), `${file}: viewport (zoom must stay allowed)`);
    assert.ok(!/user-scalable|maximum-scale/i.test(html), `${file}: zoom must not be blocked`);
    assert.equal((html.match(/<main[\s>]/g) ?? []).length, 1, `${file}: main`);
    assert.ok(/class="skip-link"/.test(html), `${file}: skip link`);
  }
});

test('the scripts never build markup from text', () => {
  for (const file of assetFiles.filter((f) => f.endsWith('.js'))) {
    const code = read(staticDir, 'assets', file).replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
    for (const banned of [/\.innerHTML/, /\.outerHTML/, /insertAdjacentHTML/, /document\.write/, /\beval\s*\(/, /new Function/,
      /\blocalStorage\b/, /\bsessionStorage\b/, /document\.cookie/, /indexedDB/, /setAttribute\(\s*['"]style/, /srcdoc/]) {
      assert.ok(!banned.test(code), `${file}: ${banned}`);
    }
  }
});

test('the stylesheet loads nothing from elsewhere', () => {
  const css = read(staticDir, 'assets', 'app.css');
  assert.ok(!/url\(|@import|https?:/i.test(css));
});
