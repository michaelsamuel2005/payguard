import { ACCOUNTS, MODE, Person, expect, mint, newCustomer, test, uniqueRef } from './support.mjs';

test.describe('a customer paying someone', () => {
  test('a usual payment to a payee whose name matches is sent straight away', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.expectScreen('Pay someone');
    await me.toCheck({ ...ACCOUNTS.john, amount: '25.00', reference: 'DINNER' });

    await expect(page.getByRole('term').filter({ hasText: 'Name check' })).toBeVisible();
    await expect(page.getByText('Name matches')).toBeVisible();
    await expect(page.getByText('£25.00')).toBeVisible();
    await me.send();

    await me.expectScreen('Payment sent');
    await expect(page.getByText('£25.00 to John Smith.')).toBeVisible();
    await expect(page.getByText('You sent this after seeing a warning.')).toHaveCount(0);

    // The payment's history is the append-only audit trail, loaded when opened.
    await page.getByText('History of this payment').click();
    await expect(page.getByText('Started', { exact: false }).first()).toBeVisible();
    await expect(page.getByText(`By ${me.sub}.`)).toBeVisible();
  });

  test('a name that is close shows the real name, then warns before sending, and the customer can cancel', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.sara));
    await me.expectScreen('The name is close, but not the same');
    await expect(page.getByText("SARAH O'CONNOR")).toBeVisible();
    await expect(page.getByText('If you carry on, we will check this payment before it is sent.')).toBeVisible();
    await me.continueToDetails();
    await me.enterDetails('25.00', uniqueRef('BOOKS'));
    await me.send();

    await me.expectScreen('The name is close, but not the same');
    await expect(page.getByText('This payment has not been sent yet')).toBeVisible();
    await expect(page.getByText('Check it is who you meant to pay.')).toBeVisible();
    await page.getByRole('button', { name: 'Cancel this payment' }).click();
    await me.expectScreen('Payment cancelled');
    await expect(page.getByText('You cancelled this payment. It has not been sent.')).toBeVisible();
  });

  test('scam wording stops the payment until the customer ticks the box, then it is sent', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '25.00', reference: 'SAFE ACCOUNT' });
    await me.send();

    await me.expectScreen('The reference looks like a common scam');
    await expect(page.getByText("Your bank and HMRC will never ask you to move money like this.")).toBeVisible();
    await expect(page.getByText('Before you decide, ask yourself')).toBeVisible();
    await expect(page.getByRole('listitem').filter({ hasText: 'Did someone contact you out of the blue' })).toBeVisible();

    // Trying to send without ticking the box is refused, with the usual error summary.
    await page.getByRole('button', { name: 'Send payment anyway' }).click();
    const summary = page.getByRole('alert');
    await expect(summary).toBeFocused();
    await expect(summary.getByRole('heading', { name: 'There is a problem' })).toBeVisible();
    await summary.getByRole('link', { name: 'Tick the box to say you have read the warning' }).click();
    await expect(page.getByLabel('I have read this warning, and I still want to send this payment')).toBeFocused();
    await expect(page.getByLabel(/I have read this warning/)).toHaveAttribute('aria-invalid', 'true');

    await page.getByLabel(/I have read this warning/).check();
    await page.getByRole('button', { name: 'Send payment anyway' }).click();
    await me.expectScreen('Payment sent');
    await expect(page.getByText('You sent this after seeing a warning.')).toBeVisible();
  });

  test('a large payment to someone new gets its own warning', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.greenfield, amount: '1,500.00', reference: 'RENT' });
    await expect(page.getByText('£1,500.00')).toBeVisible();
    await me.send();
    await me.expectScreen('This is a large payment to someone new');
  });

  test('a name that does not match is held; the customer can check for an update and then cancel', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.mike));
    await me.expectScreen('The name does not match');
    // The real account holder's name is never shown on a no-match.
    await expect(page.locator('main')).not.toContainText('PRIYA');
    await expect(page.locator('main')).not.toContainText('Priya');
    await expect(page.getByRole('link', { name: 'Choose someone else' })).toBeVisible();
    await me.continueToDetails();
    await me.enterDetails('10.00', uniqueRef('INVOICE'));
    await me.send();

    await me.expectScreen('We are checking this payment');
    await expect(page.getByText('We have not sent this payment yet.')).toBeVisible();
    // Customers are told why in words, never shown the risk score or signals.
    await expect(page.locator('main')).not.toContainText('Risk score');

    await page.getByRole('button', { name: 'Check for an update' }).click();
    await expect(page.locator('#live')).toHaveText('Checked. This payment is still being reviewed.');
    await expect(page.getByText(/^Last checked at \d\d:\d\d:\d\d$/)).toBeVisible();

    await page.getByRole('button', { name: 'Cancel this payment' }).click();
    await me.expectScreen('Payment cancelled');
  });

  test('mistakes are listed in an error summary that links to each field', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await page.getByRole('button', { name: 'Check the name and add payee' }).click();

    const summary = page.getByRole('alert');
    await expect(summary).toBeFocused();
    await expect(summary.getByRole('link')).toHaveText([
      'Enter the name on the account, up to 70 characters',
      'Enter a sort code with six digits, like 12-34-56',
      'Enter an account number with eight digits',
    ]);
    await expect(page.getByLabel('Sort code')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Sort code')).toHaveAccessibleDescription(/Six digits, for example 12-34-56.*Error: Enter a sort code/);

    await summary.getByRole('link', { name: /sort code/ }).click();
    await expect(page.getByLabel('Sort code')).toBeFocused();

    // Fix them and carry on.
    await me.addPayee('John Smith', '12 34 56', '1234 5678');
    await me.expectScreen('The name matches');
  });

  test('the amount and reference are checked before anything is sent', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.john));
    await me.continueToDetails();

    const cases = [
      ['abc', 'DINNER', 'Enter an amount in pounds and pence, like 25.00'],
      ['0', 'DINNER', 'Enter an amount more than £0.00'],
      ['12.345', 'DINNER', 'Enter an amount in pounds and pence, like 25.00'],
      ['25', 'pay £5', 'Enter a reference of 1 to 18 letters, numbers or spaces, or the characters - . / &'],
    ];
    for (const [amount, reference, message] of cases) {
      await page.getByLabel('Amount').fill(amount);
      await page.getByLabel('Payment reference').fill(reference);
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.getByRole('alert')).toContainText(message);
      await expect(page.getByLabel('Amount')).toHaveValue(amount); // what was typed is kept
    }
    // The box stops anyone typing a 19th character.
    await page.getByLabel('Payment reference').fill('x'.repeat(30));
    await expect(page.getByLabel('Payment reference')).toHaveValue('x'.repeat(18));
    // The server has the last word: £30,000 is over the limit and it says so, against the amount field.
    await me.enterDetails('30,000.00', 'DINNER');
    await me.send();
    await expect(page.getByRole('alert')).toContainText('an amount is between 1p and');
    await expect(page.getByRole('alert')).toBeFocused();
    await expect(page.getByRole('alert').getByRole('link')).toHaveAttribute('data-field', 'amount');
  });

  test('a payee already added is offered in a list, and is not added twice', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.john));
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('The name matches');

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
    await me.expectScreen('Pay someone');
    const choice = page.getByRole('radio', { name: 'John Smith' });
    await expect(choice).toHaveAccessibleDescription('Sort code 12-34-56, account ending 5678. Name matches.');

    // Adding the same account again is refused in plain words.
    await me.addPayee(...Object.values(ACCOUNTS.john));
    await expect(page.getByRole('alert')).toContainText('You have already added this account. Choose them from the list instead.');
    await expect(page.getByRole('alert')).toBeFocused();

    // Choosing from the list without choosing anyone is an error too; choosing someone goes straight on (name matched).
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Select someone to pay');
    await page.getByRole('radio', { name: 'John Smith' }).check();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await me.expectScreen('How much are you paying?');
  });

  test('pressing Back and sending again does not send the payment twice', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '12.34', reference: uniqueRef('ONCE') });
    await me.send();
    await me.expectScreen('Payment sent');

    await page.goBack();
    await me.expectScreen('Check your payment');
    await me.send();
    await me.expectScreen('Payment sent');
    await expect(page.locator('#live')).toHaveText('This payment had already been started. It has not been sent twice.');

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Your payments' }).click();
    await expect(page.getByRole('listitem').filter({ hasText: '£12.34 to John Smith' })).toHaveCount(1);
  });

  test('a payment that fails on the network can be sent again safely, with the same key', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '5.00', reference: uniqueRef('RETRY') });

    const keys = [];
    await page.route('**/v1/payments', async (route) => {
      keys.push(route.request().headers()['idempotency-key']);
      if (keys.length === 1) await route.abort('connectionreset');
      else await route.continue();
    });
    await me.send();
    // We cannot know whether it was started, so we do not say "nothing was sent".
    await expect(page.getByRole('alert')).toContainText('We could not confirm whether this payment was started.');
    await expect(page.getByRole('alert')).toContainText('the same details will not be sent twice');
    await me.send();
    await me.expectScreen('Payment sent');
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  test('changing the details starts a new payment, with a new key', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '5.00', reference: uniqueRef('KEYS') });
    const keys = [];
    await page.route('**/v1/payments', async (route) => { keys.push(route.request().headers()['idempotency-key']); await route.continue(); });
    await me.send();
    await me.expectScreen('Payment sent');
    await page.goBack();
    await page.getByRole('link', { name: 'Change details' }).click();
    await page.getByLabel('Amount').fill('6.00');
    await page.getByRole('button', { name: 'Continue' }).click();
    await me.send();
    await me.expectScreen('Payment sent');
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  test('too many payments too quickly is explained, with the wait', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.toCheck({ ...ACCOUNTS.john, amount: '5.00', reference: uniqueRef('SLOW') });
    await page.route('**/v1/payments', (route) => route.fulfill({
      status: 429, headers: { 'Retry-After': '12', 'Content-Type': 'application/problem+json' },
      body: JSON.stringify({ status: 429, detail: 'too many payment requests' }),
    }));
    await me.send();
    await expect(page.getByRole('alert')).toContainText('You are sending payments too quickly. Wait 12 seconds and try again.');
  });

  test('a service outage is explained without a status code', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await page.route('**/v1/payees', (route) => route.fulfill({ status: 503, body: '' }));
    await page.reload();
    await expect(me.h1).toHaveText('Sign in to the demo'); // a reload signs you out: the token lives in memory only
    await me.signIn();
    await expect(me.h1).toHaveText('Something went wrong');
    await expect(page.getByText('Something went wrong on our side. Try again in a moment.')).toBeVisible();
    await page.unroute('**/v1/payees');
    await page.getByRole('button', { name: 'Try again' }).click();
    await me.expectScreen('Pay someone');
  });

  test('when the account\'s bank cannot be asked, the name is never silently passed', async ({ page }) => {
    test.skip(MODE !== 'harness', 'needs the harness\'s one account that always fails');
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.unreachable));
    await me.expectScreen('We could not check the name');
    await me.continueToDetails();
    await me.enterDetails('25.00', uniqueRef('DOWN'));
    await me.send();
    await me.expectScreen('We could not check who owns this account');
  });
});

test.describe('signing in and out', () => {
  test('a token pasted into the sign-in form works, and is never stored', async ({ page }) => {
    const sub = newCustomer();
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sign in to the demo');
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toBeFocused();
    await expect(page.getByRole('alert')).toContainText('Enter an access token');

    await page.getByLabel('Access token').fill('not a token');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('That does not look like an access token');

    await page.getByLabel('Access token').fill(mint(sub));
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Pay someone');
    await expect(page.getByText(`Signed in as ${sub}`)).toBeVisible();

    // Nothing is kept anywhere the page could be read back from.
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length, document.cookie])).toEqual([0, 0, '']);
    expect(await page.context().cookies()).toEqual([]);
    expect(page.url()).not.toContain(mint(sub).slice(0, 20));

    // Reloading forgets the token: a signed-in page must be signed in again.
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sign in to the demo');
  });

  test('a token in the link is taken out of the address bar at once', async ({ page }) => {
    const me = new Person(page);
    const token = mint(me.sub);
    await page.goto(`/#token=${token}`);
    await me.expectScreen('Pay someone');
    expect(page.url()).not.toContain('token');
    expect(page.url()).not.toContain(token.slice(0, 20));
    expect(await page.evaluate(() => location.hash)).toBe('#/pay');
    // Not in the history either: going Back does not bring the token back.
    await page.goBack().catch(() => undefined);
    expect(page.url()).not.toContain(token.slice(0, 20));
  });

  test('an expired token or one with no useful scope is turned away at the door', async ({ page }) => {
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const make = (claims) => `${b64({ alg: 'none' })}.${b64(claims)}.c2ln`;
    await page.goto('/');
    await page.getByLabel('Access token').fill(make({ sub: 'x', scope: 'payments', exp: 1000 }));
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('That token has expired. Get a new one.');
    await page.getByLabel('Access token').fill(make({ sub: 'x', scope: 'metrics' }));
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('neither the payments nor the reviews scope');
  });

  test('a server that stops accepting the token signs the person out with a reason', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await page.route('**/v1/payees', (route) => route.fulfill({ status: 401, headers: { 'WWW-Authenticate': 'Bearer' }, body: '' }));
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Your payments' }).click();
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Pay someone' }).click();
    await expect(me.h1).toHaveText('Sign in to the demo');
    await expect(page.getByText('Your session has ended. Sign in again.')).toBeVisible();
  });

  test('signing out forgets everything', async ({ page }) => {
    const me = new Person(page);
    await me.signIn();
    await me.addPayee(...Object.values(ACCOUNTS.john));
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(me.h1).toHaveText('Sign in to the demo');
    await expect(page.getByText('You have signed out.')).toBeVisible();
    await page.goBack().catch(() => undefined);
    if (page.url().startsWith('http')) await expect(me.h1).toHaveText('Sign in to the demo');
  });

  test('opening a sign-in link in a tab that is already open signs in as the new person, with none of the old data', async ({ page }) => {
    const first = new Person(page);
    await first.signIn();
    await first.addPayee(...Object.values(ACCOUNTS.john));
    await expect(first.h1).toHaveText('The name matches');
    const second = new Person(page);
    await page.goto(`/#token=${mint(second.sub)}`); // only the fragment changes, so the page is not reloaded
    await second.expectScreen('Pay someone');
    await expect(page.getByText(`Signed in as ${second.sub}`)).toBeVisible();
    await expect(page.getByRole('radio')).toHaveCount(0); // the first person's payee is not here
    expect(page.url()).not.toContain('token');
  });

  test('an address with no page, or the wrong sign-in for it, says so', async ({ page }) => {
    const me = new Person(page);
    await me.signIn('payments', '#/nowhere');
    await expect(me.h1).toHaveText('Page not found');
    await page.evaluate(() => { location.hash = '#/review'; });
    await expect(me.h1).toHaveText('You cannot use this page');
    await page.evaluate(() => { location.hash = '#/payment/not-an-id'; });
    await expect(me.h1).toHaveText('Page not found');
    await page.evaluate(() => { location.hash = '#/details'; });
    await expect(me.h1).toHaveText('Pay someone'); // needs earlier answers: starts the journey again
  });
});
