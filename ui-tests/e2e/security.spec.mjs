import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ACCOUNTS, Person, expect, mint, newCustomer, test } from './support.mjs';

const repo = fileURLToPath(new URL('../..', import.meta.url));
const shippedPolicy = (() => {
  const src = readFileSync(`${repo}/src/main/java/io/github/michaelsamuel2005/payguard/config/SecurityConfig.java`, 'utf8');
  const literal = src.match(/CONTENT_SECURITY_POLICY\s*=\s*((?:"[^"]*"\s*\+?\s*)+);/)[1];
  return [...literal.matchAll(/"([^"]*)"/g)].map((m) => m[1]).join('');
})();

test.describe('what the server sends with the page', () => {
  for (const path of ['/', '/index.html', '/about.html', '/accessibility.html', '/assets/app.js', '/assets/app.css']) {
    test(`${path} is public and carries the security headers`, async ({ request }) => {
      const response = await request.get(path);
      expect(response.status()).toBe(200);
      const h = response.headers();
      expect(h['content-security-policy']).toBe(shippedPolicy);
      expect(h['referrer-policy']).toBe('no-referrer');
      expect(h['x-content-type-options']).toBe('nosniff');
      expect(h['x-frame-options'] ?? h['content-security-policy']).toMatch(/DENY|frame-ancestors 'none'/i);
      expect(h['permissions-policy']).toContain('camera=()');
      expect(h['permissions-policy']).toContain('geolocation=()');
      expect(h['cache-control']).toMatch(/no-store|no-cache/);
    });
  }

  test('scripts and styles are served with types the browser will accept under nosniff', async ({ request }) => {
    expect((await request.get('/assets/app.js')).headers()['content-type']).toMatch(/^(text|application)\/javascript/);
    expect((await request.get('/assets/format.js')).headers()['content-type']).toMatch(/^(text|application)\/javascript/);
    expect((await request.get('/assets/app.css')).headers()['content-type']).toMatch(/^text\/css/);
    expect((await request.get('/assets/favicon.svg')).headers()['content-type']).toMatch(/^image\/svg\+xml/);
  });

  test('the page is public but the API and everything else is not', async ({ request }) => {
    for (const path of ['/v1/payees', '/v1/payments/00000000-0000-4000-8000-000000000000', '/v1/reviews', '/actuator/prometheus']) {
      const response = await request.get(path);
      expect(response.status(), path).toBe(401);
      expect(response.headers()['www-authenticate']).toMatch(/^Bearer/);
    }
    expect((await request.post('/')).status()).not.toBe(200);
    expect((await request.get('/assets/does-not-exist.js')).status()).toBe(404);
    expect((await request.get('/actuator/health')).status()).toBe(200);
  });

  test('API answers are never cached', async ({ request }) => {
    const response = await request.get('/v1/payees', { headers: { Authorization: `Bearer ${mint(newCustomer())}` } });
    expect(response.status()).toBe(200);
    expect(response.headers()['cache-control']).toMatch(/no-store|no-cache/);
  });
});

test.describe('what the page does with what it is given', () => {
  test('a payee name that looks like markup is shown as text, and nothing runs', async ({ page }) => {
    const dialogs = [];
    page.on('dialog', async (d) => { dialogs.push(d.message()); await d.dismiss(); });
    const me = new Person(page);
    await me.signIn();
    const nasty = '<img src=x onerror=alert(1)> <b>bold</b>';
    await me.addPayee(nasty, '99-99-99', '11111111');
    await me.expectScreen('The name does not match'); // an account the directory does not know
    await expect(page.getByText(nasty, { exact: true })).toBeVisible();
    await expect(page.locator('main img, main b, main script')).toHaveCount(0);

    // And again where it comes back from the server as a list.
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
    await expect(page.getByRole('radio', { name: nasty })).toBeVisible();
    await expect(page.locator('main img, main b, main script')).toHaveCount(0);
    expect(dialogs).toEqual([]);
  });

  test('the page talks only to its own server, sends no Referer, and keeps no token anywhere', async ({ page }) => {
    const origin = new URL(test.info().project.use.baseURL).origin;
    const requests = [];
    page.on('request', (r) => requests.push({ url: r.url(), referer: r.headers().referer }));
    const me = new Person(page);
    const token = mint(me.sub);
    await page.goto(`/#token=${token}`);
    await me.addPayee(...Object.values(ACCOUNTS.john));
    await me.continueToDetails();
    await me.enterDetails('9.99', 'SEC');
    await me.send();
    await me.expectScreen('Payment sent');

    for (const r of requests) expect(new URL(r.url).origin, r.url).toBe(origin);
    const api = requests.filter((r) => r.url.includes('/v1/'));
    expect(api.map((r) => new URL(r.url).pathname)).toEqual(expect.arrayContaining(['/v1/payees', '/v1/payments']));
    expect(api.length).toBeGreaterThanOrEqual(3);
    for (const r of api) expect(r.referer ?? '', r.url).toBe('');
    for (const r of requests) expect(r.url, 'a token must never be in a URL').not.toContain(token.slice(0, 24));

    const stored = await page.evaluate(() => JSON.stringify([localStorage, sessionStorage, document.cookie]));
    expect(stored).not.toContain(token.slice(0, 24));
    expect(await page.context().cookies()).toEqual([]);
  });

  test('the page works with nothing but the policy allows: no inline script or style is needed', async ({ page }) => {
    // The guard fixture fails every test on a CSP violation. This one makes it explicit, and visits every page.
    const me = new Person(page);
    await me.signIn();
    for (const path of ['/about.html', '/accessibility.html', '/']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    }
    const violations = await page.evaluate(() => window.__csp ?? []);
    expect(violations).toEqual([]);
  });
});
