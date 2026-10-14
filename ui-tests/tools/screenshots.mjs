// Takes the pictures in docs/screens from a running payguard (docker compose up), the way a phone shows them.
//   PAYGUARD_URL=http://localhost:8080 node tools/screenshots.mjs
// Uses the "harness" token shape when PAYGUARD_E2E=harness, and scripts/DevToken.java otherwise.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ACCOUNTS, Person, mint, uniqueRef } from '../e2e/support.mjs';

const out = fileURLToPath(new URL('../../docs/screens/', import.meta.url));
mkdirSync(out, { recursive: true });
const base = process.env.PAYGUARD_URL ?? 'http://localhost:8080';
const browser = await chromium.launch();

async function shoot(page, name) {
  await page.waitForTimeout(150);
  await page.screenshot({ path: `${out}${name}.png`, fullPage: true });
  console.log('wrote', `docs/screens/${name}.png`);
}

const phone = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true, locale: 'en-GB', baseURL: base };
for (const scheme of ['light', 'dark']) {
  const context = await browser.newContext({ ...phone, colorScheme: scheme });
  const page = await context.newPage();
  const me = new Person(page);
  await me.signIn();
  const suffix = scheme === 'dark' ? '-dark' : '';
  if (scheme === 'light') await shoot(page, '01-pay');
  await me.addPayee(...Object.values(ACCOUNTS.sara));
  await me.expectScreen('The name is close, but not the same');
  if (scheme === 'light') await shoot(page, '02-name-close-match');
  // A scam-wording warning needs a payee whose name matches (a close match plus scam words would be held instead).
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
  await me.addPayee(...Object.values(ACCOUNTS.john));
  await me.continueToDetails();
  await me.enterDetails('25.00', 'SAFE ACCOUNT');
  await me.send();
  await page.getByText('This payment has not been sent yet').waitFor();
  await shoot(page, `03-scam-warning${suffix}`);
  if (scheme === 'light') {
    await page.getByRole('button', { name: 'Send payment anyway' }).click();
    await shoot(page, '04-warning-needs-the-box-ticked');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
    await me.addPayee(...Object.values(ACCOUNTS.mike));
    await me.continueToDetails();
    const held = uniqueRef('INVOICE');
    await me.enterDetails('4,000.00', held);
    await me.send();
    await page.getByRole('heading', { name: 'We are checking this payment' }).waitFor();
    await shoot(page, '05-held-for-a-check');

    const desk = await (await browser.newContext({ ...phone, colorScheme: scheme })).newPage();
    const rita = new Person(desk, 'rita');
    await rita.signIn('reviews');
    await rita.expectScreen('Held payments');
    await shoot(desk, '06-review-queue');
    await desk.getByRole('listitem').filter({ hasText: held }).getByRole('link', { name: /^Review/ }).click();
    await rita.expectScreen('Review a held payment');
    await shoot(desk, '07-review-a-held-payment');
  }
  await context.close();
}
await browser.close();
void mint;
