// Accessibility checks. The screens are walked once, and every check runs on every screen state reached:
//   axe-core (WCAG 2.0, 2.1 and 2.2 A and AA, plus best practice), page structure, and target size.
// The same walk runs at 320 CSS pixels wide, at 200% text size, and with wider text spacing, to look for sideways
// scrolling. Keyboard-only journeys are in the second half. Automated checks find some problems, not all:
// accessibility.html says what was not checked (screen readers above all).
import AxeBuilder from '@axe-core/playwright';
import { ACCOUNTS, MODE, Person, expect, mint, newCustomer, test, uniqueRef } from './support.mjs';

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

async function axeProblems(page) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  return violations.map((v) => `${v.id} (${v.impact}): ${v.help} -> ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

/** The things axe does not cover and this page promises: one h1, heading order, landmarks, ids, titles, target size. */
async function structureProblems(page) {
  return page.evaluate(() => {
    const out = [];
    const shown = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    if (document.documentElement.lang !== 'en-GB') out.push(`html lang is "${document.documentElement.lang}"`);
    if (!/\S.* – payguard$/.test(document.title)) out.push(`title "${document.title}" does not say what the screen is`);
    const h1s = [...document.querySelectorAll('h1')].filter(shown);
    if (h1s.length !== 1) out.push(`${h1s.length} visible h1 elements`);
    let previous = 0;
    for (const h of document.querySelectorAll('h1, h2, h3, h4, h5, h6')) {
      const level = Number(h.tagName[1]);
      if (previous && level > previous + 1) out.push(`heading order jumps h${previous} to h${level}: "${h.textContent}"`);
      previous = level;
    }
    for (const [selector, name] of [['main', 'main'], ['header', 'banner'], ['footer', 'contentinfo']]) {
      const n = document.querySelectorAll(selector).length;
      if (n !== 1) out.push(`${n} ${name} landmarks`);
    }
    const navs = [...document.querySelectorAll('nav')].filter((n) => !n.hidden);
    const names = navs.map((n) => n.getAttribute('aria-label'));
    if (names.some((x) => !x) || new Set(names).size !== names.length) out.push(`navigation landmarks need unique names: ${names}`);
    const ids = [...document.querySelectorAll('[id]')].map((e) => e.id);
    const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
    if (dupes.length) out.push(`duplicate ids: ${dupes}`);
    for (const el of document.querySelectorAll('button, input:not([type=hidden]), textarea, select, summary, a.btn, a.nav__link')) {
      if (!shown(el) || el.closest('.visually-hidden')) continue;
      const r = el.getBoundingClientRect();
      if (r.width < 24 || r.height < 24) out.push(`target ${el.tagName.toLowerCase()} "${(el.textContent || el.id).trim().slice(0, 30)}" is ${Math.round(r.width)}x${Math.round(r.height)}`);
    }
    return out;
  });
}

/** Anything wider than the window, or text spilling out of a button. */
async function overflowProblems(page) {
  return page.evaluate(() => {
    const out = [];
    const w = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth > w) out.push(`page scrolls sideways: ${document.documentElement.scrollWidth} wide in a ${w} window`);
    for (const el of document.querySelectorAll('body *')) {
      if (el.closest('.visually-hidden') || el.classList.contains('skip-link')) continue;
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.right > w + 1) out.push(`${el.tagName.toLowerCase()}.${el.className} "${(el.textContent || '').trim().slice(0, 30)}" ends at ${Math.round(r.right)}`);
    }
    for (const el of document.querySelectorAll('.btn, .badge, .input')) {
      if (el.scrollWidth > el.clientWidth + 1) out.push(`text spills out of ${el.tagName.toLowerCase()}.${el.className}`);
    }
    return out.slice(0, 8);
  });
}

/** Reaches every screen state a customer and a reviewer can see, calling `visit(label)` on each. */
async function walk(page, browser, visit) {
  const me = new Person(page);
  const alert = page.getByRole('alert');
  const main = page.getByRole('navigation', { name: 'Main' });

  await page.goto('/');
  await expect(me.h1).toHaveText('Sign in to the demo');
  await visit('sign in');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(alert).toBeFocused();
  await visit('sign in with errors');

  await page.getByLabel('Access token').fill(mint(me.sub));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await me.expectScreen('Pay someone');
  await visit('pay, no payees yet');
  await page.getByRole('button', { name: 'Check the name and add payee' }).click();
  await expect(alert).toBeFocused();
  await visit('pay, with errors');

  await me.addPayee(...Object.values(ACCOUNTS.john));
  await me.expectScreen('The name matches');
  await visit('name check: match');
  await main.getByRole('link', { name: 'Pay someone' }).click();
  await expect(page.getByRole('radio', { name: 'John Smith' })).toBeVisible();
  await visit('pay, with a payee');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await expect(alert).toBeFocused();
  await visit('pay, nobody chosen');
  await page.getByRole('radio', { name: 'John Smith' }).check();
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await me.expectScreen('How much are you paying?');
  await visit('amount and reference');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(alert).toBeFocused();
  await visit('amount and reference, with errors');
  await me.enterDetails('25.00', uniqueRef('DINNER'));
  await visit('check your payment');
  await me.send();
  await me.expectScreen('Payment sent');
  await visit('payment sent');
  await page.getByText('History of this payment').click();
  await expect(page.getByText('By ', { exact: false }).first()).toBeVisible();
  await visit('payment sent, history open');

  await main.getByRole('link', { name: 'Pay someone' }).click();
  await me.addPayee(...Object.values(ACCOUNTS.sara));
  await me.expectScreen('The name is close, but not the same');
  await visit('name check: close match');
  await me.continueToDetails();
  await me.enterDetails('25.00', uniqueRef('BOOKS'));
  await me.send();
  await expect(page.getByText('This payment has not been sent yet')).toBeVisible();
  await visit('warning');
  await page.getByRole('button', { name: 'Send payment anyway' }).click();
  await expect(alert).toBeFocused();
  await visit('warning, with error');
  await page.getByRole('button', { name: 'Cancel this payment' }).click();
  await me.expectScreen('Payment cancelled');
  await visit('payment cancelled');

  await main.getByRole('link', { name: 'Pay someone' }).click();
  await me.addPayee(...Object.values(ACCOUNTS.mike));
  await me.expectScreen('The name does not match');
  await visit('name check: no match');
  await me.continueToDetails();
  const heldRef = uniqueRef('INVOICE');
  await me.enterDetails('10.00', heldRef);
  await me.send();
  await me.expectScreen('We are checking this payment');
  await visit('held for a check');
  await main.getByRole('link', { name: 'Your payments' }).click();
  await me.expectScreen('Your payments');
  await visit('your payments');

  if (MODE === 'harness') {
    await main.getByRole('link', { name: 'Pay someone' }).click();
    await me.addPayee(...Object.values(ACCOUNTS.unreachable));
    await me.expectScreen('We could not check the name');
    await visit('name check: could not check');
  }

  await page.evaluate(() => { location.hash = '#/nowhere'; });
  await expect(me.h1).toHaveText('Page not found');
  await visit('page not found');
  await page.route('**/v1/payees', (route) => route.fulfill({ status: 503, body: '' }));
  await page.evaluate(() => { location.hash = '#/pay'; });
  await expect(me.h1).toHaveText('Something went wrong');
  await visit('something went wrong');
  await page.unroute('**/v1/payees');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByText('You have signed out.')).toBeVisible();
  await visit('sign in, signed out');

  const context = await browser.newContext({
    baseURL: test.info().project.use.baseURL, locale: 'en-GB', timezoneId: 'Europe/London',
    colorScheme: test.info().project.use.colorScheme, viewport: page.viewportSize(), bypassCSP: test.info().project.use.bypassCSP,
  });
  const desk = await context.newPage();
  try {
    const rita = new Person(desk, `rev-${newCustomer().slice(-8)}`);
    await rita.signIn('reviews');
    await rita.expectScreen('Held payments');
    await expect(desk.getByRole('heading', { name: new RegExp(heldRef) })).toBeVisible();
    await visit('review queue', desk);
    await desk.getByRole('listitem').filter({ has: desk.getByRole('heading', { name: new RegExp(heldRef) }) })
      .getByRole('link', { name: /^Review/ }).click();
    await rita.expectScreen('Review a held payment');
    await visit('review a held payment', desk);
    await desk.getByRole('button', { name: 'Save decision' }).click();
    await expect(desk.getByRole('alert')).toBeFocused();
    await visit('review, with errors', desk);
    await desk.getByRole('radio', { name: 'Reject' }).check();
    await desk.getByLabel('Reason').fill('Payee asked by text message');
    await desk.getByRole('button', { name: 'Save decision' }).click();
    await rita.expectScreen('Decision saved');
    await visit('decision saved', desk);
  } finally {
    await context.close();
  }

  for (const path of ['/about.html', '/accessibility.html']) {
    await page.goto(path);
    await expect(me.h1).toBeVisible();
    await visit(path);
  }
}

test.describe.configure({ timeout: 180_000 });

for (const scheme of ['light', 'dark']) {
  test.describe(`${scheme} colour scheme`, () => {
    test.use({ colorScheme: scheme });

    test(`every screen passes axe, and has sound structure and big enough targets`, async ({ page, browser }) => {
      const failures = [];
      await walk(page, browser, async (label, which = page) => {
        const problems = [...(await axeProblems(which)), ...(await structureProblems(which))];
        for (const p of problems) failures.push(`[${label}] ${p}`);
      });
      expect(failures).toEqual([]);
    });
  });
}

test.describe('reflow and text size', () => {
  const cases = {
    'at 320 CSS pixels wide': { viewport: { width: 320, height: 640 }, css: '' },
    'at 320 CSS pixels wide with text at 200%': { viewport: { width: 320, height: 640 }, css: 'html { font-size: 200% !important; }' },
    'with the wider text spacing WCAG 1.4.12 asks pages to cope with': {
      viewport: { width: 360, height: 640 },
      css: '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }',
    },
  };
  for (const [name, { viewport, css }] of Object.entries(cases)) {
    test.describe(name, () => {
      test.use({ viewport, bypassCSP: Boolean(css) }); // injecting a test stylesheet needs the policy out of the way
      test('no screen scrolls sideways or lets text spill out', async ({ page, browser }) => {
        const failures = [];
        if (css) await page.addInitScript((c) => {
          const apply = () => { const s = document.createElement('style'); s.textContent = c; document.head.append(s); };
          if (document.head) apply(); else document.addEventListener('DOMContentLoaded', apply);
        }, css);
        await walk(page, browser, async (label, which = page) => {
          for (const p of await overflowProblems(which)) failures.push(`[${label}] ${p}`);
        });
        expect(failures).toEqual([]);
      });
    });
  }
});

// ------------------------------------------------------------------------------------------------ keyboard

/** Presses Tab until the element has focus, as a keyboard user would, and returns how many presses it took. */
async function tabTo(page, locator, limit = 80) {
  for (let presses = 1; presses <= limit; presses += 1) {
    await page.keyboard.press('Tab');
    if (await locator.evaluate((el) => el === document.activeElement).catch(() => false)) return presses;
  }
  throw new Error(`Tab never reached ${locator} in ${limit} presses`);
}

/** The focused element must show a focus indicator: an outline of at least 2px. */
async function expectFocusVisible(page) {
  const style = await page.evaluate(() => {
    const s = getComputedStyle(document.activeElement);
    return { outlineStyle: s.outlineStyle, outlineWidth: parseFloat(s.outlineWidth), shadow: s.boxShadow };
  });
  expect(style.outlineStyle).not.toBe('none');
  expect(style.outlineWidth).toBeGreaterThanOrEqual(2);
}

const focusedIsHeading = (page) => page.evaluate(() => document.activeElement?.tagName === 'H1');

test.describe('using only a keyboard', () => {
  test('the first Tab offers a skip link that moves focus to the main content', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to main content' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeInViewport();
    await page.keyboard.press('Enter');
    await expect(page.locator('main')).toBeFocused();
  });

  test('a payment can be made from start to finish, and focus follows the page', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    const john = ACCOUNTS.john;

    await tabTo(page, page.getByLabel('Name on the account'));
    await expectFocusVisible(page);
    await page.keyboard.type(john.name);
    await tabTo(page, page.getByLabel('Sort code'));
    await page.keyboard.type(john.sortCode);
    await tabTo(page, page.getByLabel('Account number'));
    await page.keyboard.type(john.accountNumber);
    await tabTo(page, page.getByRole('button', { name: 'Check the name and add payee' }));
    await expectFocusVisible(page);
    await page.keyboard.press('Enter');
    await me.expectScreen('The name matches');
    expect(await focusedIsHeading(page)).toBe(true);

    await tabTo(page, page.getByRole('link', { name: 'Continue to payment' }));
    await expectFocusVisible(page);
    await page.keyboard.press('Enter');
    await me.expectScreen('How much are you paying?');
    expect(await focusedIsHeading(page)).toBe(true);

    await tabTo(page, page.getByLabel('Amount'));
    await page.keyboard.type('25.00');
    await tabTo(page, page.getByLabel('Payment reference'));
    await page.keyboard.type('KEYBOARD');
    await tabTo(page, page.getByRole('button', { name: 'Continue' }));
    await page.keyboard.press('Space');
    await me.expectScreen('Check your payment');
    expect(await focusedIsHeading(page)).toBe(true);

    await tabTo(page, page.getByRole('button', { name: 'Send payment' }));
    await page.keyboard.press('Enter');
    await me.expectScreen('Payment sent');
    expect(await focusedIsHeading(page)).toBe(true);
  });

  test('a mistake moves focus to the error summary, and its link moves focus to the field', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await tabTo(page, page.getByRole('button', { name: 'Check the name and add payee' }));
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert')).toBeFocused();
    await expectFocusVisible(page);
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: /name on the account/ })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Name on the account')).toBeFocused();
  });

  test('a warning can be read, ticked, and confirmed from the keyboard, and cancel comes first', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '25.00', reference: 'SAFE ACCOUNT' });
    await tabTo(page, page.getByRole('button', { name: 'Send payment' }));
    await page.keyboard.press('Enter');
    await expect(page.getByText('This payment has not been sent yet')).toBeVisible();
    expect(await focusedIsHeading(page)).toBe(true);

    const tick = page.getByLabel(/I have read this warning/);
    const cancel = page.getByRole('button', { name: 'Cancel this payment' });
    const confirm = page.getByRole('button', { name: 'Send payment anyway' });
    await tabTo(page, tick);
    await page.keyboard.press('Space');
    await expect(tick).toBeChecked();
    await page.keyboard.press('Tab');
    await expect(cancel).toBeFocused(); // the safe choice is reached before the risky one
    await page.keyboard.press('Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Enter');
    await me.expectScreen('Payment sent');
  });

  test('a reviewer can open a held payment and decide on it from the keyboard', async ({ page, browser }) => {
    const customer = new Person(page);
    await customer.signIn();
    await customer.addPayee(...Object.values(ACCOUNTS.mike));
    await customer.continueToDetails();
    const reference = uniqueRef('KEYS');
    await customer.enterDetails('10.00', reference);
    await customer.send();
    await customer.expectScreen('We are checking this payment');

    const context = await browser.newContext({ baseURL: test.info().project.use.baseURL, locale: 'en-GB', timezoneId: 'Europe/London' });
    const desk = await context.newPage();
    try {
      const rita = new Person(desk, `rev-${newCustomer().slice(-8)}`);
      await rita.signIn('reviews');
      await rita.expectScreen('Held payments');
      const link = desk.getByRole('listitem').filter({ has: desk.getByRole('heading', { name: new RegExp(reference) }) })
        .getByRole('link', { name: /^Review/ });
      await tabTo(desk, link);
      await expectFocusVisible(desk);
      await desk.keyboard.press('Enter');
      await rita.expectScreen('Review a held payment');
      expect(await focusedIsHeading(desk)).toBe(true);

      await tabTo(desk, desk.getByRole('radio', { name: 'Approve' }));
      await desk.keyboard.press('ArrowDown'); // radio groups move with the arrow keys
      await expect(desk.getByRole('radio', { name: 'Reject' })).toBeChecked();
      await desk.keyboard.press('ArrowUp');
      await expect(desk.getByRole('radio', { name: 'Approve' })).toBeChecked();
      await tabTo(desk, desk.getByLabel('Reason'));
      await desk.keyboard.type('Checked by phone');
      await tabTo(desk, desk.getByRole('button', { name: 'Save decision' }));
      await desk.keyboard.press('Enter');
      await rita.expectScreen('Decision saved');
    } finally {
      await context.close();
    }
  });
});

test.describe('names, roles and the way the page tells you things', () => {
  test('form fields have labels, hints and errors that a screen reader would join up', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await expect(page.getByRole('textbox', { name: 'Name on the account' })).toHaveAccessibleDescription(
      'The name of the person or business, as you were told it. We check it against the account.');
    await expect(page.getByRole('textbox', { name: 'Sort code' })).toHaveAccessibleDescription('Six digits, for example 12-34-56');
    await expect(page.getByRole('textbox', { name: 'Account number' })).toHaveAccessibleDescription('Eight digits, for example 12345678');
    await page.getByRole('button', { name: 'Check the name and add payee' }).click();
    await expect(page.getByRole('textbox', { name: 'Account number' })).toHaveAccessibleDescription(
      /Eight digits, for example 12345678.*Error: Enter an account number with eight digits/);
    await expect(page.getByRole('textbox', { name: 'Account number' })).toHaveAttribute('aria-invalid', 'true');
  });

  test('the warning page reads in a sensible order', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '25.00', reference: 'SAFE ACCOUNT' });
    await me.send();
    await expect(page.getByRole('main')).toMatchAriaSnapshot(`
      - paragraph: This payment has not been sent yet
      - heading "The reference looks like a common scam" [level=1]
      - heading "Before you decide, ask yourself" [level=2]
      - list:
        - listitem: Did someone contact you out of the blue, or tell you to act quickly?
        - listitem: Did you get these bank details from someone you cannot check?
        - listitem: Have you checked the request using a phone number or website you already trust?
    `);
    await expect(page.getByRole('checkbox', { name: 'I have read this warning, and I still want to send this payment' })).not.toBeChecked();
    await expect(page.getByRole('button')).toHaveText(['Sign out', 'Cancel this payment', 'Send payment anyway']);
  });

  test('the current section is marked in the navigation, and every screen has its own title', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link', { name: 'Pay someone' })).toHaveAttribute('aria-current', 'page');
    await expect(nav.getByRole('link', { name: 'Your payments' })).not.toHaveAttribute('aria-current', 'page');
    await expect(page).toHaveTitle('Pay someone – payguard');
    await nav.getByRole('link', { name: 'Your payments' }).click();
    await expect(page).toHaveTitle('Your payments – payguard');
    await expect(nav.getByRole('link', { name: 'Your payments' })).toHaveAttribute('aria-current', 'page');
  });

  test('status messages are announced politely, without moving focus', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await expect(page.locator('#live')).toHaveAttribute('role', 'status');
    await expect(page.locator('#live')).toHaveAttribute('aria-live', 'polite');
    await me.addPayee(...Object.values(ACCOUNTS.mike));
    await me.continueToDetails();
    await me.enterDetails('10.00', uniqueRef('HELD'));
    await me.send();
    await me.expectScreen('We are checking this payment');
    const check = page.getByRole('button', { name: 'Check for an update' });
    await check.focus();
    await check.click();
    await expect(page.locator('#live')).toHaveText('Checked. This payment is still being reviewed.');
    await expect(check).toBeFocused(); // the screen was not redrawn, so the person's place is not lost
  });

  test('buttons that are busy say so and ignore a second press', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '3.00', reference: uniqueRef('BUSY') });
    let posts = 0;
    await page.route('**/v1/payments', async (route) => {
      posts += 1;
      await new Promise((r) => setTimeout(r, 600));
      await route.continue();
    });
    const send = page.getByRole('button', { name: 'Send payment' });
    await send.click();
    await expect(page.getByRole('button', { name: 'Sending…' })).toHaveAttribute('aria-disabled', 'true');
    await page.getByRole('button', { name: 'Sending…' }).click({ force: true }).catch(() => undefined);
    await me.expectScreen('Payment sent');
    expect(posts).toBe(1);
  });
});
