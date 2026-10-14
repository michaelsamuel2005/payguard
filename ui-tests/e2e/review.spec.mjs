import { ACCOUNTS, Person, expect, mint, newCustomer, test, uniqueRef } from './support.mjs';

/** A customer starts a payment that the rules hold (a name that does not match, to a new payee). */
async function heldPayment(page, amount = '10.00') {
  const customer = new Person(page);
  await customer.signIn();
  await customer.addPayee(...Object.values(ACCOUNTS.mike));
  await customer.continueToDetails();
  const reference = uniqueRef('INVOICE');
  await customer.enterDetails(amount, reference);
  await customer.send();
  await customer.expectScreen('We are checking this payment');
  return { customer, reference };
}

async function reviewer(browser, sub = newCustomer(), scope = 'reviews') {
  const context = await browser.newContext({
    baseURL: test.info().project.use.baseURL, locale: 'en-GB', timezoneId: 'Europe/London',
    ...(test.info().project.use.isMobile ? {} : {}),
  });
  const page = await context.newPage();
  const person = new Person(page, `rev-${sub.slice(-8)}`);
  await person.signIn(scope);
  return { person, page, context };
}

const cardFor = (page, reference) => page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: new RegExp(reference) }) });

test.describe('the review desk', () => {
  test('a reviewer approves a held payment, and the customer then sees it sent', async ({ page, browser }) => {
    const { customer, reference } = await heldPayment(page);
    const { person: rita, page: desk, context } = await reviewer(browser);
    try {
      await rita.expectScreen('Held payments');
      // A reviewer has no business paying anyone: the customer screens are not on offer.
      const nav = desk.getByRole('navigation', { name: 'Main' });
      await expect(nav.getByRole('link')).toHaveText(['Review desk']);

      const card = cardFor(desk, reference);
      await expect(card).toContainText('£10.00');
      await expect(card).toContainText('Risk score 4');
      await expect(card).toContainText('First payment to this payee');
      await expect(card).toContainText('Name does not match the account');
      await card.getByRole('link', { name: /^Review/ }).click();

      await rita.expectScreen('Review a held payment');
      await expect(desk.getByText('Reference', { exact: true })).toBeVisible();
      await expect(desk.getByText(reference, { exact: true })).toBeVisible();
      await desk.getByText('History of this payment').click();
      await expect(desk.getByText('Started').first()).toBeVisible();

      // A decision needs a choice and a reason.
      await desk.getByRole('button', { name: 'Save decision' }).click();
      await expect(desk.getByRole('alert').getByRole('link')).toHaveText(['Choose approve or reject', 'Say why you made this decision']);
      await expect(desk.getByRole('alert')).toBeFocused();
      await desk.getByRole('radio', { name: 'Approve' }).check();
      await desk.getByLabel('Reason').fill('Confirmed with the supplier on a number we already hold');
      await desk.getByRole('button', { name: 'Save decision' }).click();

      await rita.expectScreen('Decision saved');
      await expect(desk.getByText('You approved and sent the £10.00 payment')).toBeVisible();
      await desk.getByRole('link', { name: 'Back to held payments' }).click();
      await expect(cardFor(desk, reference)).toHaveCount(0);
    } finally {
      await context.close();
    }

    // The customer finds out the next time they look.
    await page.getByRole('button', { name: 'Check for an update' }).click();
    await customer.expectScreen('Payment sent');
    await page.getByText('History of this payment').click();
    await expect(page.getByText('Approved by a reviewer')).toBeVisible();
    await expect(page.getByText('Confirmed with the supplier on a number we already hold')).toBeVisible();
  });

  test('a reviewer rejects a held payment, and the customer is told it was stopped', async ({ page, browser }) => {
    const { customer, reference } = await heldPayment(page, '20.00');
    const { person: rita, page: desk, context } = await reviewer(browser);
    try {
      await cardFor(desk, reference).getByRole('link', { name: /^Review/ }).click();
      await desk.getByRole('radio', { name: 'Reject' }).check();
      await desk.getByLabel('Reason').fill('The payee asked for this by text message');
      await desk.getByRole('button', { name: 'Save decision' }).click();
      await rita.expectScreen('Decision saved');
      await expect(desk.getByText('You rejected the £20.00 payment')).toBeVisible();
    } finally {
      await context.close();
    }
    await page.getByRole('button', { name: 'Check for an update' }).click();
    await customer.expectScreen('This payment was stopped');
    await expect(page.getByText('It has not been sent.')).toBeVisible();
  });

  test('nobody can review their own payment, even with both scopes', async ({ page }) => {
    const me = new Person(page);
    await me.signIn('payments reviews');
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link')).toHaveText(['Pay someone', 'Your payments', 'Review desk']);
    await me.addPayee(...Object.values(ACCOUNTS.mike));
    await me.continueToDetails();
    const reference = uniqueRef('OWN');
    await me.enterDetails('15.00', reference);
    await me.send();
    await me.expectScreen('We are checking this payment');

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Review desk' }).click();
    await cardFor(page, reference).getByRole('link', { name: /^Review/ }).click();
    await page.getByRole('radio', { name: 'Approve' }).check();
    await page.getByLabel('Reason').fill('Looks fine to me');
    await page.getByRole('button', { name: 'Save decision' }).click();
    await expect(page.getByRole('alert')).toContainText('a reviewer cannot decide on their own payment');
    await expect(page.getByRole('alert')).toBeFocused();
    // What they typed is not lost, and the payment is still held.
    await page.getByRole('link', { name: 'Back to held payments' }).click();
    await expect(cardFor(page, reference)).toHaveCount(1);
  });

  test('a payment that is no longer waiting says so', async ({ browser }) => {
    const { person: rita, page: desk, context } = await reviewer(browser);
    try {
      await rita.expectScreen('Held payments');
      await desk.evaluate(() => { location.hash = '#/review/00000000-0000-4000-8000-000000000000'; });
      await expect(rita.h1).toHaveText('This payment is not waiting for review');
      await desk.getByRole('link', { name: 'Back to held payments' }).click();
      await rita.expectScreen('Held payments');
    } finally {
      await context.close();
    }
  });

  test('refreshing the list tells a screen-reader user how many are waiting', async ({ browser }) => {
    const { person: rita, page: desk, context } = await reviewer(browser);
    try {
      await rita.expectScreen('Held payments');
      await desk.getByRole('button', { name: 'Refresh the list' }).click();
      await expect(desk.locator('#live')).toHaveText(/^\d+ payments? (is|are) waiting for review\.$/);
    } finally {
      await context.close();
    }
  });

  test('a customer cannot open the review desk, and a reviewer cannot open the payment screens', async ({ page, browser }) => {
    const me = new Person(page);
    await me.signIn();
    await page.evaluate(() => { location.hash = '#/review'; });
    await expect(me.h1).toHaveText('You cannot use this page');
    await expect(page.getByText('Your sign-in does not have the reviews scope.')).toBeVisible();

    const { person: rita, page: desk, context } = await reviewer(browser);
    try {
      await desk.evaluate(() => { location.hash = '#/pay'; });
      await expect(rita.h1).toHaveText('You cannot use this page');
    } finally {
      await context.close();
    }
  });

  test('the server refuses a customer token on the review API, whatever the page does', async ({ request }) => {
    const response = await request.get('/v1/reviews', { headers: { Authorization: `Bearer ${mint(newCustomer())}` } });
    expect(response.status()).toBe(403);
  });
});
