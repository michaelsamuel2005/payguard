import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, test as base } from '@playwright/test';

const repo = fileURLToPath(new URL('../..', import.meta.url));

/**
 * "real": the Spring application in docker compose, with tokens signed by scripts/DevToken.java (CI).
 * "harness": a stand-in for the web layer used where Spring cannot be built; it reads a token's claims but not its
 * signature, so tokens are made here. Tests that need the harness's extra controls are skipped in "real".
 */
export const MODE = process.env.PAYGUARD_E2E ?? 'real';

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

export function mint(sub, scope = 'payments') {
  if (MODE === 'real') {
    return execFileSync('java', ['scripts/DevToken.java', '--sub', sub, '--scope', scope],
      { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  }
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({ iss: 'harness', sub, scope, exp })}.c2lnbmF0dXJl`;
}

export const newCustomer = () => `cust-${randomUUID().slice(0, 8)}`;
/** A reference nobody else's test will use, within the API's 18 characters. */
export const uniqueRef = (word) => `${word} ${randomUUID().slice(0, 5)}`.slice(0, 18);

/**
 * Every test also fails on: a script error, a console error (except the browser's own note about an HTTP error
 * status, which some tests cause on purpose), or any Content-Security-Policy violation.
 */
export const test = base.extend({
  guard: [async ({ page }, use) => {
    const problems = [];
    page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
    page.on('console', (m) => {
      if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) problems.push(`console error: ${m.text()}`);
    });
    await page.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (e) => {
        (window.__csp ??= []).push(`${e.violatedDirective} blocked ${e.blockedURI}`);
      });
    });
    await use();
    const violations = await page.evaluate(() => window.__csp ?? []).catch(() => []);
    expect([...problems, ...violations.map((v) => `CSP violation: ${v}`)]).toEqual([]);
  }, { auto: true }],
});

export { expect };

/** Drives the screens the way a person does: by label, button and link names, never by CSS selectors. */
export class Person {
  constructor(page, sub = newCustomer()) {
    this.page = page;
    this.sub = sub;
  }

  get h1() { return this.page.getByRole('heading', { level: 1 }); }

  async signIn(scope = 'payments', route = '') {
    await this.page.goto(`/#token=${mint(this.sub, scope)}`);
    await expect(this.h1).toBeVisible();
    if (route) await this.page.evaluate((r) => { location.hash = r; }, route);
  }

  async expectScreen(title) {
    await expect(this.h1).toHaveText(title);
    await expect(this.page).toHaveTitle(new RegExp(`– payguard$`));
  }

  async addPayee(name, sortCode, accountNumber) {
    await this.page.getByLabel('Name on the account').fill(name);
    await this.page.getByLabel('Sort code').fill(sortCode);
    await this.page.getByLabel('Account number').fill(accountNumber);
    await this.page.getByRole('button', { name: 'Check the name and add payee' }).click();
  }

  async continueToDetails() {
    await this.page.getByRole('link', { name: /^(Continue to payment|Continue anyway)$/ }).click();
    await this.expectScreen('How much are you paying?');
  }

  async enterDetails(amount, reference) {
    await this.page.getByLabel('Amount').fill(amount);
    await this.page.getByLabel('Payment reference').fill(reference);
    await this.page.getByRole('button', { name: 'Continue' }).click();
    await this.expectScreen('Check your payment');
  }

  async send() {
    await this.page.getByRole('button', { name: 'Send payment' }).click();
  }

  /** Adds a payee and walks to the "Check your payment" screen. */
  async toCheck({ name, sortCode, accountNumber, amount, reference }) {
    await this.addPayee(name, sortCode, accountNumber);
    await expect(this.page.getByText(/^The name /).first()).toBeVisible();
    await this.continueToDetails();
    await this.enterDetails(amount, reference);
  }
}

export const ACCOUNTS = {
  john: { name: 'John Smith', sortCode: '12-34-56', accountNumber: '12345678' },
  sara: { name: "Sara O'Connor", sortCode: '12-34-56', accountNumber: '87654321' }, // real name SARAH O'CONNOR
  greenfield: { name: 'Greenfield Lettings Ltd', sortCode: '40-50-60', accountNumber: '55556666' },
  mike: { name: 'Mike Jones', sortCode: '20-30-40', accountNumber: '33334444' }, // real holder: Priya Patel
  unreachable: { name: 'Dana Down', sortCode: '99-99-99', accountNumber: '00000000' }, // harness only: its bank never answers
};
