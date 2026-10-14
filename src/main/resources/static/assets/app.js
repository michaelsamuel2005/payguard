// payguard's screens: send money, read a scam warning, wait for a review, decide on a held payment.
// Plain ES modules, no framework, no build step. Routes are URL fragments (#/pay), so Back, Forward and
// bookmarks work, and a fragment never reaches the server. Every screen follows the same rules:
//   - one <h1>, and focus moves to it when the screen changes, so keyboard and screen-reader users land at the top;
//   - mistakes are listed in an error summary that takes focus, and each one links to its field;
//   - nothing is told by colour alone; every status has words;
//   - values from the server are only ever added as text (see dom.js), never as markup.
import { h, clear, focusElement } from './dom.js';
import { createApi } from './api.js';
import * as fmt from './format.js';
import * as copy from './copy.js';

const $main = document.getElementById('main');
const $nav = document.getElementById('nav');
const $who = document.getElementById('who');
const $live = document.getElementById('live');
const SITE = 'payguard';

const state = {
  token: null, // lives in this variable only: not in storage, not in the URL. Reloading the tab signs you out.
  session: null, // {sub, scopes, exp}, read from the token for display
  payees: null,
  draft: null, // {payee, amountText, referenceText, pence, reference, key}
  payments: new Map(), // payments seen in this visit; the API has no "list my payments", only "get by id"
  confirmed: new Set(), // payment ids the customer released after a warning
  queue: null, // the review queue, for reviewers
  notice: null, // shown once on the sign-in screen
};

const can = (scope) => Boolean(state.session?.scopes.includes(scope));
const api = createApi({
  getToken: () => state.token,
  onUnauthorised: () => endSession('Your session has ended. Sign in again.'),
});

// ---------------------------------------------------------------------------------------------------- building blocks

function announce(text) {
  $live.textContent = '';
  setTimeout(() => { $live.textContent = text; }, 60);
}

const time = (iso) => {
  const d = new Date(iso);
  return h('time', { datetime: iso }, new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(d));
};

function button(text, { kind = 'primary', type = 'button', onClick } = {}) {
  const b = h('button', { type, class: `btn btn--${kind}` }, text);
  if (onClick) b.addEventListener('click', onClick);
  return b;
}

function linkButton(href, text, kind = 'primary') {
  return h('a', { href, class: `btn btn--${kind}` }, text);
}

function badge(kind, text) {
  const glyph = { success: '✓', warning: '!', danger: '✕', neutral: '•' }[kind] ?? '•';
  return h('span', { class: `badge badge--${kind}` }, h('span', { 'aria-hidden': 'true' }, glyph), text);
}

function callout(kind, ...children) {
  return h('div', { class: `callout callout--${kind}` }, ...children);
}

function summary(rows) {
  return h('dl', { class: 'summary' },
    rows.filter(Boolean).map(([k, v]) => h('div', { class: 'summary__row' }, h('dt', {}, k), h('dd', {}, v))));
}

/** The GOV.UK-style error summary: a heading, and one link per mistake. It takes focus when it appears. */
function errorSummary(errors) {
  if (!errors.length) return null;
  const box = h('div', { class: 'error-summary', role: 'alert', tabindex: '-1', 'aria-labelledby': 'error-summary-title' },
    h('h2', { id: 'error-summary-title' }, 'There is a problem'),
    h('ul', {}, errors.map((e) => h('li', {}, e.field
      ? h('a', { href: `#${e.field}`, 'data-field': e.field }, e.message)
      : e.message))));
  box.addEventListener('click', (event) => {
    const link = event.target.closest('a[data-field]');
    if (!link) return;
    event.preventDefault();
    const target = document.getElementById(link.dataset.field);
    if (target) { target.scrollIntoView({ block: 'center' }); target.focus(); }
  });
  return box;
}

function fieldError(id, errors) {
  return errors.find((e) => e.field === id);
}

/** A labelled text input with an optional hint and error, wired together for assistive technology. */
function textField({ id, label, hint, value = '', errors = [], inputmode, maxlength, autocomplete = 'off', type = 'text',
  prefix, wide = false }) {
  const err = fieldError(id, errors);
  const describedBy = [hint && `${id}-hint`, err && `${id}-error`].filter(Boolean).join(' ');
  const input = h('input', {
    id, name: id, type, value, inputmode, maxlength, autocomplete, spellcheck: 'false', autocapitalize: 'off',
    class: 'input', 'aria-describedby': describedBy || false, 'aria-invalid': err ? 'true' : false,
  });
  return h('div', { class: `field${err ? ' field--error' : ''}` },
    h('label', { for: id, class: 'label' }, label),
    hint && h('p', { id: `${id}-hint`, class: 'hint' }, hint),
    err && h('p', { id: `${id}-error`, class: 'field-error' }, h('span', { class: 'visually-hidden' }, 'Error: '), err.message),
    prefix
      ? h('div', { class: 'input-group' }, h('span', { class: 'input-group__prefix', 'aria-hidden': 'true' }, prefix), input)
      : h('div', { class: wide ? '' : 'input-wrap' }, input));
}

/** Runs an action while a button shows a busy label. It ignores a second press, so nothing is sent twice. */
async function whileBusy(btn, busyLabel, action) {
  if (btn.getAttribute('aria-disabled') === 'true') return undefined;
  const label = btn.textContent;
  btn.setAttribute('aria-disabled', 'true');
  btn.textContent = busyLabel;
  announce(busyLabel);
  try {
    return await action();
  } finally {
    if (btn.isConnected) { btn.removeAttribute('aria-disabled'); btn.textContent = label; }
  }
}

// ---------------------------------------------------------------------------------------------------- routing

let renderSeq = 0;
let firstMount = true;

function parseRoute() {
  const raw = location.hash.startsWith('#/') ? location.hash.slice(2) : '';
  const [name = '', id = ''] = raw.split('/');
  return { name, id: /^[0-9a-fA-F-]{36}$/.test(id) ? id : '', rawId: id };
}

/** Returned by a screen that cannot be shown yet (it needs answers from an earlier step). */
class Redirect {
  constructor(hash) { this.hash = hash; }
}

function go(hash) {
  if (location.hash === hash) render(); else location.hash = hash;
}

function redirect(hash) {
  history.replaceState(null, '', `${location.pathname}${location.search}${hash}`);
}

/** Puts a screen on the page. `focus` says where keyboard focus goes; the default is the screen's <h1>. */
function mount(view, { focus } = {}) {
  clear($main);
  $main.append(view.node);
  document.title = `${view.title} – ${SITE}`;
  const target = focus ?? $main.querySelector('h1');
  if (!firstMount && target) focusElement(target);
  if (view.node.querySelector('h1')) firstMount = false;
}

function mountWithErrors(build, errors) {
  const view = build(errors);
  mount(view, { focus: view.node.querySelector('.error-summary') });
}

function loadingView() {
  return { title: 'Loading', node: h('p', { role: 'status', class: 'loading' }, 'Loading…') };
}

async function render() {
  const seq = ++renderSeq;
  renderChrome();
  const route = parseRoute();
  if (!state.session) return mount(signInView());
  if (route.name === '') {
    redirect(can('payments') ? '#/pay' : '#/review');
    return render();
  }
  const screens = {
    pay: ['payments', payView], 'name-check': ['payments', nameCheckView], details: ['payments', detailsView],
    check: ['payments', checkView], payment: ['payments', paymentView], payments: ['payments', paymentsView],
    review: ['reviews', reviewView],
  };
  const entry = screens[route.name];
  if (!entry) return mount(messageView('Page not found', 'There is no page at this address.'));
  if (!can(entry[0])) {
    return mount(messageView('You cannot use this page', `Your sign-in does not have the ${entry[0]} scope.`));
  }
  if (route.rawId && !route.id) return mount(messageView('Page not found', 'That address is not valid.'));
  mount(loadingView());
  let view;
  try {
    view = await entry[1](route);
  } catch (e) {
    if (e.status === 401) return undefined;
    view = failureView(e);
  }
  if (seq !== renderSeq) return undefined;
  if (view instanceof Redirect) {
    redirect(view.hash);
    return render();
  }
  mount(view);
  return undefined;
}

function renderChrome() {
  clear($nav);
  clear($who);
  $nav.hidden = !state.session;
  if (!state.session) return;
  const route = parseRoute().name;
  const items = [
    ['payments', '#/pay', 'Pay someone', ['pay', 'name-check', 'details', 'check']],
    ['payments', '#/payments', 'Your payments', ['payments', 'payment']],
    ['reviews', '#/review', 'Review desk', ['review']],
  ].filter(([scope]) => can(scope));
  $nav.append(h('ul', { class: 'nav__list' }, items.map(([, href, text, names]) =>
    h('li', {}, h('a', { href, class: 'nav__link', 'aria-current': names.includes(route) ? 'page' : false }, text)))));
  const out = button('Sign out', { kind: 'link', onClick: () => endSession('You have signed out.') });
  $who.append(h('span', { class: 'who__name' }, `Signed in as ${state.session.sub}`), out);
}

function endSession(notice) {
  clearSessionData();
  Object.assign(state, { token: null, session: null, notice });
  renderSeq += 1;
  redirect('#/');
  render();
}

function messageView(title, text, ...extra) {
  return { title, node: h('div', {}, h('h1', {}, title), h('p', {}, text), ...extra) };
}

function failureView(error) {
  return messageView('Something went wrong', copy.problemMessage(error),
    h('div', { class: 'actions' }, button('Try again', { onClick: () => render() })));
}

// ---------------------------------------------------------------------------------------------------- sign in

function trySignIn(text) {
  const token = String(text).trim();
  if (!token) return { field: 'token', message: 'Enter an access token' };
  const session = fmt.readToken(token);
  if (!session) return { field: 'token', message: 'That does not look like an access token. It has three parts, separated by dots.' };
  if (session.exp !== null && session.exp * 1000 <= Date.now()) {
    return { field: 'token', message: 'That token has expired. Get a new one.' };
  }
  if (!session.scopes.includes('payments') && !session.scopes.includes('reviews')) {
    return { field: 'token', message: 'That token has neither the payments nor the reviews scope, so there is nothing to show.' };
  }
  clearSessionData(); // a different person must not inherit the last person's payees or payments
  Object.assign(state, { token, session, notice: null });
  return null;
}

function clearSessionData() {
  Object.assign(state, { payees: null, draft: null, queue: null });
  state.payments.clear();
  state.confirmed.clear();
}

function signInView(errors = []) {
  const notice = state.notice;
  const form = h('form', { novalidate: true });
  form.append(
    textField({ id: 'token', label: 'Access token', type: 'password', errors, wide: true,
      hint: 'A signed token (JWT). It stays in this tab\'s memory and is gone when you close or reload the tab.' }),
    h('div', { class: 'actions' }, button('Sign in', { type: 'submit' })));
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const problem = trySignIn(form.elements.token.value);
    if (!problem) { redirect('#/'); render(); return; }
    mountWithErrors((errs) => signInView(errs), [problem]);
  });
  return {
    title: 'Sign in',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'Sign in to the demo'),
      notice && callout('info', h('p', {}, notice)),
      h('p', {}, 'payguard is an API. It has no login screen of its own. It trusts a signed access token, the way a bank\'s identity provider would hand one to an app.'),
      form,
      h('details', { class: 'details' },
        h('summary', {}, 'How to get a token for this demo'),
        h('p', {}, 'In the project folder, a customer token:'),
        h('pre', { class: 'code' }, h('code', {}, 'java scripts/DevToken.java --sub alice --scope payments')),
        h('p', {}, 'A reviewer token:'),
        h('pre', { class: 'code' }, h('code', {}, 'java scripts/DevToken.java --sub rita --scope reviews')),
        h('p', {}, 'Or run scripts/demo-links.sh, which prints two links that sign you in.')),
      h('p', {}, h('a', { href: 'about.html' }, 'Try the demo: what to type and what should happen'))),
  };
}

// ---------------------------------------------------------------------------------------------------- send money

async function loadPayees(force = false) {
  if (force || !state.payees) state.payees = await api.listPayees();
  return state.payees;
}

const accountLine = (p) => `Sort code ${p.sortCode}, account ending ${fmt.lastFour(p.accountNumber)}`;

async function payView() {
  state.draft = null;
  return buildPayView(await loadPayees(true), [], {});
}

/** Builds the pay screen. A mistake redraws it from the same list, without asking the server again. */
function buildPayView(payees, errors, values) {
  const redraw = (errs, typed) => mountWithErrors((e) => buildPayView(payees, e, typed ?? values), errs);

  let choose = null;
  if (payees.length) {
    const err = fieldError('payee-choice', errors);
    choose = h('form', { id: 'choose-payee', novalidate: true },
      h('fieldset', { class: 'fieldset', 'aria-describedby': err ? 'payee-choice-error' : false },
        h('legend', { class: 'legend' }, 'Pay someone you have added before'),
        err && h('p', { id: 'payee-choice-error', class: 'field-error' }, h('span', { class: 'visually-hidden' }, 'Error: '), err.message),
        payees.map((p, i) => {
          const id = i === 0 ? 'payee-choice' : `payee-choice-${i}`;
          return h('div', { class: 'choice' },
            h('input', { type: 'radio', name: 'payee', id, value: p.id, 'aria-describedby': `${id}-hint` }),
            h('div', {}, h('label', { for: id, class: 'label' }, p.name),
              h('p', { id: `${id}-hint`, class: 'hint' }, `${accountLine(p)}. ${copy.NAME_CHECK[p.nameCheck]?.label ?? ''}.`)));
        })),
      h('div', { class: 'actions' }, button('Continue', { type: 'submit' })));
    choose.addEventListener('submit', (event) => {
      event.preventDefault();
      const picked = choose.querySelector('input[name="payee"]:checked')?.value;
      const payee = payees.find((p) => p.id === picked);
      if (!payee) { redraw([{ field: 'payee-choice', message: 'Select someone to pay' }]); return; }
      state.draft = { payee, amountText: '', referenceText: '', pence: null, reference: null, key: null };
      go(payee.nameCheck === 'MATCH' ? '#/details' : '#/name-check');
    });
  }

  const form = h('form', { id: 'new-payee', novalidate: true });
  form.append(
    textField({ id: 'payee-name', label: 'Name on the account', value: values.name ?? '', errors, maxlength: 70, wide: true,
      hint: 'The name of the person or business, as you were told it. We check it against the account.' }),
    textField({ id: 'sort-code', label: 'Sort code', value: values.sortCode ?? '', errors, inputmode: 'numeric', maxlength: 8,
      hint: 'Six digits, for example 12-34-56' }),
    textField({ id: 'account-number', label: 'Account number', value: values.accountNumber ?? '', errors, inputmode: 'numeric',
      maxlength: 11, hint: 'Eight digits, for example 12345678' }),
    h('div', { class: 'actions' }, button('Check the name and add payee', { type: 'submit' })));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const typed = { name: form.elements['payee-name'].value, sortCode: form.elements['sort-code'].value,
      accountNumber: form.elements['account-number'].value };
    const problems = [];
    if (!fmt.isValidPayeeName(typed.name)) problems.push({ field: 'payee-name', message: 'Enter the name on the account, up to 70 characters' });
    const sc = fmt.parseSortCode(typed.sortCode);
    if (!sc) problems.push({ field: 'sort-code', message: 'Enter a sort code with six digits, like 12-34-56' });
    const an = fmt.parseAccountNumber(typed.accountNumber);
    if (!an) problems.push({ field: 'account-number', message: 'Enter an account number with eight digits' });
    if (problems.length) { redraw(problems, typed); return; }
    const submit = event.submitter ?? form.querySelector('button[type="submit"]');
    await whileBusy(submit, 'Checking the name…', async () => {
      try {
        const payee = await api.registerPayee({ name: typed.name.trim(), sortCode: fmt.formatSortCode(sc), accountNumber: an });
        state.payees = [...(state.payees ?? []), payee];
        state.draft = { payee, amountText: '', referenceText: '', pence: null, reference: null, key: null };
        go('#/name-check');
      } catch (e) {
        if (e.status === 401) return;
        const duplicate = e.status === 409;
        redraw([{
          field: duplicate ? 'account-number' : fmt.fieldForProblem(e.problem?.detail),
          message: duplicate ? 'You have already added this account. Choose them from the list instead.' : copy.problemMessage(e),
        }], typed);
      }
    });
  });

  return {
    title: 'Pay someone',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'Pay someone'),
      h('p', { class: 'lead' }, 'We check the name against the account before you pay, and we look for signs of a scam.'),
      choose,
      choose && h('hr', { class: 'rule' }),
      h('h2', {}, choose ? 'Or add a new payee' : 'Add a payee'),
      form),
  };
}

function nameCheckView() {
  const draft = state.draft;
  if (!draft) return new Redirect('#/pay');
  const { payee } = draft;
  const text = copy.NAME_CHECK[payee.nameCheck] ?? copy.NAME_CHECK.UNAVAILABLE;
  const careful = payee.nameCheck !== 'MATCH';
  const actions = payee.nameCheck === 'NO_MATCH'
    ? [linkButton('#/pay', 'Choose someone else'), linkButton('#/details', 'Continue anyway', 'danger')]
    : [linkButton('#/details', 'Continue to payment'), linkButton('#/pay', 'Choose someone else', 'secondary')];
  return {
    title: text.title,
    node: h('div', {},
      h('h1', {}, text.title),
      callout(text.kind, h('p', {}, badge(text.kind, text.label)), h('p', {}, text.text)),
      summary([
        ['Name you gave', payee.name],
        payee.accountName && ['Name on the account', payee.accountName],
        ['Sort code', payee.sortCode],
        ['Account number', `ending ${fmt.lastFour(payee.accountNumber)}`],
      ]),
      careful && h('p', {}, 'If you carry on, we will check this payment before it is sent.'),
      h('div', { class: 'actions' }, actions)),
  };
}

function detailsView(route, errors = [], values = null) {
  const draft = state.draft;
  if (!draft) return new Redirect('#/pay');
  const v = values ?? { amount: draft.amountText, reference: draft.referenceText };
  const form = h('form', { novalidate: true });
  form.append(
    textField({ id: 'amount', label: 'Amount', prefix: '£', inputmode: 'decimal', value: v.amount, errors,
      hint: 'In pounds and pence, for example 25.00' }),
    textField({ id: 'reference', label: 'Payment reference', value: v.reference, errors, maxlength: 18, wide: true,
      hint: 'The person you pay will see this. Up to 18 letters, numbers or spaces, or the characters - . / &' }),
    h('div', { class: 'actions' }, button('Continue', { type: 'submit' }), linkButton('#/pay', 'Change payee', 'secondary')));
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const typed = { amount: form.elements.amount.value, reference: form.elements.reference.value };
    const pence = fmt.parsePounds(typed.amount);
    const problems = [];
    if (pence === null) problems.push({ field: 'amount', message: 'Enter an amount in pounds and pence, like 25.00' });
    else if (pence <= 0) problems.push({ field: 'amount', message: 'Enter an amount more than £0.00' });
    if (!fmt.isValidReference(typed.reference)) {
      problems.push({ field: 'reference', message: 'Enter a reference of 1 to 18 letters, numbers or spaces, or the characters - . / &' });
    }
    if (problems.length) { mountWithErrors((errs) => detailsView(route, errs, typed), problems); return; }
    const reference = typed.reference.trim();
    // A new key whenever the details change; the same key when only retrying the same details.
    if (draft.key === null || draft.pence !== pence || draft.reference !== reference) draft.key = fmt.newIdempotencyKey();
    Object.assign(draft, { amountText: typed.amount, referenceText: typed.reference, pence, reference });
    go('#/check');
  });
  return {
    title: 'Amount and reference',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'How much are you paying?'),
      callout('info', h('p', {}, h('strong', {}, draft.payee.name), h('br'), accountLine(draft.payee))),
      form),
  };
}

function checkView(route, errors = []) {
  const draft = state.draft;
  if (!draft || draft.pence === null) return new Redirect('#/pay');
  const { payee } = draft;
  const nc = copy.NAME_CHECK[payee.nameCheck] ?? copy.NAME_CHECK.UNAVAILABLE;
  const form = h('form', { novalidate: true });
  form.append(h('div', { class: 'actions' },
    button('Send payment', { type: 'submit' }), linkButton('#/details', 'Change details', 'secondary')));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const submit = event.submitter ?? form.querySelector('button[type="submit"]');
    await whileBusy(submit, 'Sending…', async () => {
      try {
        const result = await api.initiatePayment(draft.key, { payeeId: payee.id, amountPence: draft.pence, reference: draft.reference });
        state.payments.set(result.data.id, result.data);
        if (result.replayed) announce('This payment had already been started. It has not been sent twice.');
        go(`#/payment/${result.data.id}`);
      } catch (e) {
        if (e.status === 401) return;
        const known = [400, 404, 409, 429].includes(e.status);
        const message = known ? copy.problemMessage(e) : copy.SEND_UNCERTAIN;
        mountWithErrors((errs) => checkView(route, errs), [{ field: known ? fmt.fieldForProblem(e.problem?.detail) : null, message }]);
      }
    });
  });
  return {
    title: 'Check your payment',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'Check your payment'),
      summary([
        ['To', payee.name],
        ['Sort code', payee.sortCode],
        ['Account number', `ending ${fmt.lastFour(payee.accountNumber)}`],
        ['Name check', badge(nc.kind, nc.label)],
        ['Amount', fmt.formatPence(draft.pence)],
        ['Reference', draft.reference],
      ]),
      form),
  };
}

// ---------------------------------------------------------------------------------------------------- a payment

const payeeName = (payment) => state.payees?.find((p) => p.id === payment.payeeId)?.name ?? 'the payee';

function paymentRows(p) {
  const status = copy.PAYMENT_STATUS[p.status] ?? { kind: 'neutral', label: p.status };
  return summary([
    ['To', payeeName(p)],
    ['Amount', fmt.formatPence(p.amountPence)],
    ['Reference', p.reference],
    ['Status', badge(status.kind, status.label)],
    ['Started', time(p.createdAt)],
  ]);
}

/** The payment's audit trail, loaded when the person opens the disclosure. */
function historyDisclosure(p, load) {
  const body = h('div', { class: 'history' });
  const details = h('details', { class: 'details' }, h('summary', {}, 'History of this payment'), body);
  let loaded = false;
  details.addEventListener('toggle', async () => {
    if (!details.open || loaded) return;
    loaded = true;
    clear(body);
    body.append(h('p', { role: 'status' }, 'Loading…'));
    try {
      const entries = await load(p.id);
      clear(body);
      body.append(h('ol', { class: 'timeline' }, entries.map((e) => h('li', {},
        h('strong', {}, copy.AUDIT_ACTIONS[e.action] ?? e.action), ' ', time(e.at),
        h('br'), `By ${e.actor}. ${e.detail}`))));
    } catch (e) {
      loaded = false;
      clear(body);
      if (e.status !== 401) body.append(h('p', {}, copy.problemMessage(e)));
    }
  });
  return details;
}

async function paymentView(route, errors = []) {
  let payment = state.payments.get(route.id);
  if (!payment) {
    payment = await api.getPayment(route.id);
    state.payments.set(payment.id, payment);
  }
  await loadPayees().catch(() => undefined);
  switch (payment.status) {
    case 'RELEASED': return releasedView(payment);
    case 'AWAITING_CONFIRMATION': return warningView(payment, errors);
    case 'HELD': return heldView(payment, errors);
    case 'REJECTED':
      return endedView(payment, 'This payment was stopped', 'A reviewer decided not to send this payment. It has not been sent.');
    default:
      return endedView(payment, 'Payment cancelled', 'You cancelled this payment. It has not been sent.');
  }
}

function paymentActions() {
  return h('div', { class: 'actions' },
    linkButton('#/pay', 'Make another payment'), linkButton('#/payments', 'See your payments', 'secondary'));
}

function releasedView(p) {
  return {
    title: 'Payment sent',
    node: h('div', {},
      h('h1', {}, 'Payment sent'),
      callout('success', h('p', {}, badge('success', 'Sent'), ` ${fmt.formatPence(p.amountPence)} to ${payeeName(p)}.`),
        state.confirmed.has(p.id) && h('p', {}, 'You sent this after seeing a warning.')),
      paymentRows(p),
      historyDisclosure(p, (id) => api.paymentAudit(id)),
      paymentActions()),
  };
}

function endedView(p, title, text) {
  return {
    title,
    node: h('div', {}, h('h1', {}, title), callout('neutral', h('p', {}, text)), paymentRows(p), paymentActions()),
  };
}

function warningView(p, errors) {
  const code = p.warning?.code;
  const heading = copy.WARNING_HEADINGS[code] ?? 'Check this payment before you send it';
  const err = fieldError('acknowledge', errors);
  const form = h('form', { novalidate: true });
  const checkbox = h('input', { type: 'checkbox', id: 'acknowledge', name: 'acknowledge',
    'aria-describedby': err ? 'acknowledge-error' : false, 'aria-invalid': err ? 'true' : false });
  form.append(
    h('div', { class: `field${err ? ' field--error' : ''}` },
      err && h('p', { id: 'acknowledge-error', class: 'field-error' }, h('span', { class: 'visually-hidden' }, 'Error: '), err.message),
      h('div', { class: 'choice' }, checkbox,
        h('label', { for: 'acknowledge', class: 'label' }, 'I have read this warning, and I still want to send this payment'))),
    h('div', { class: 'actions' },
      button('Cancel this payment', { onClick: (event) => cancelPayment(p, event.currentTarget) }),
      button('Send payment anyway', { kind: 'danger', type: 'submit' })));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!checkbox.checked) {
      mountWithErrors((errs) => warningView(p, errs), [{ field: 'acknowledge', message: 'Tick the box to say you have read the warning' }]);
      return;
    }
    const submit = event.submitter ?? form.querySelector('button[type="submit"]');
    await whileBusy(submit, 'Sending…', async () => {
      try {
        const released = await api.confirmPayment(p.id, code);
        state.payments.set(released.id, released);
        state.confirmed.add(released.id);
        render();
      } catch (e) {
        if (e.status === 401) return;
        mountWithErrors((errs) => warningView(p, errs), [{ message: copy.problemMessage(e) }]);
      }
    });
  });
  return {
    title: heading,
    node: h('div', {},
      errorSummary(errors),
      h('p', { class: 'eyebrow' }, 'This payment has not been sent yet'),
      h('h1', {}, heading),
      callout('warning', h('p', { class: 'callout__text' }, p.warning?.message)),
      h('h2', {}, 'Before you decide, ask yourself'),
      h('ul', {}, copy.STOP_AND_THINK.map((q) => h('li', {}, q))),
      paymentRows(p),
      form),
  };
}

async function cancelPayment(p, btn) {
  await whileBusy(btn, 'Cancelling…', async () => {
    try {
      state.payments.set(p.id, await api.cancelPayment(p.id));
      render();
    } catch (e) {
      if (e.status === 401) return;
      mountWithErrors((errs) => heldView(p, errs), [{ message: copy.problemMessage(e) }]);
    }
  });
}

function heldView(p, errors) {
  const checked = h('span');
  const check = button('Check for an update', { kind: 'secondary', onClick: async (event) => {
    await whileBusy(event.currentTarget, 'Checking…', async () => {
      try {
        const fresh = await api.getPayment(p.id);
        state.payments.set(fresh.id, fresh);
        if (fresh.status !== p.status) { render(); return; }
        const now = new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        checked.textContent = `Last checked at ${now}`;
        announce('Checked. This payment is still being reviewed.');
      } catch (e) {
        if (e.status !== 401) mountWithErrors((errs) => heldView(p, errs), [{ message: copy.problemMessage(e) }]);
      }
    });
  } });
  return {
    title: 'We are checking this payment',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'We are checking this payment'),
      callout('warning', h('p', {}, badge('warning', 'Held for a check')),
        h('p', {}, 'We have not sent this payment yet. A member of our review team will look at it and either send it or stop it.'),
        p.warning && h('p', {}, h('strong', {}, 'Why: '), p.warning.message)),
      paymentRows(p),
      h('div', { class: 'actions' }, check,
        button('Cancel this payment', { kind: 'danger', onClick: (event) => cancelPayment(p, event.currentTarget) })),
      h('p', { class: 'hint' }, checked),
      historyDisclosure(p, (id) => api.paymentAudit(id))),
  };
}

function paymentsView() {
  const list = [...state.payments.values()].reverse();
  return {
    title: 'Your payments',
    node: h('div', {},
      h('h1', {}, 'Your payments'),
      h('p', { class: 'lead' }, 'Payments you have started in this visit. This list is kept in this browser tab only.'),
      list.length
        ? h('ul', { class: 'cards' }, list.map((p) => {
          const status = copy.PAYMENT_STATUS[p.status] ?? { kind: 'neutral', label: p.status };
          return h('li', { class: 'card' },
            h('h2', { class: 'card__title' }, `${fmt.formatPence(p.amountPence)} to ${payeeName(p)}`),
            h('p', {}, badge(status.kind, status.label), ' ', p.reference),
            h('p', {}, h('a', { href: `#/payment/${p.id}` }, 'View payment', h('span', { class: 'visually-hidden' }, `: ${p.reference}`))));
        }))
        : callout('info', h('p', {}, 'You have not started a payment in this visit.')),
      h('div', { class: 'actions' }, linkButton('#/pay', 'Pay someone'))),
  };
}

// ---------------------------------------------------------------------------------------------------- review desk

async function reviewView(route, errors = []) {
  if (route.id) return reviewDetailView(route.id, errors);
  state.queue = await api.reviewQueue();
  const queue = state.queue;
  return {
    title: 'Held payments',
    node: h('div', {},
      h('h1', {}, 'Held payments'),
      h('p', { class: 'lead' }, 'Oldest first. Look at why each one was held, then approve or reject it and say why.'),
      queue.length
        ? h('ul', { class: 'cards' }, queue.map((p) => h('li', { class: 'card' },
          h('h2', { class: 'card__title' }, `${fmt.formatPence(p.amountPence)} · ${p.reference}`),
          h('p', {}, badge('warning', `Risk score ${p.riskScore}`), ' Held ', time(p.createdAt)),
          h('ul', { class: 'plain' }, p.signals.map((s) => h('li', {}, copy.SIGNAL_LABELS[s] ?? s))),
          linkButton(`#/review/${p.id}`, 'Review', 'secondary'))))
        : callout('info', h('p', {}, 'No payments are waiting for review.')),
      h('div', { class: 'actions' }, button('Refresh the list', { kind: 'secondary', onClick: async (event) => {
        await whileBusy(event.currentTarget, 'Refreshing…', async () => {
          await render();
          const n = state.queue?.length ?? 0;
          announce(`${n} ${n === 1 ? 'payment is' : 'payments are'} waiting for review.`);
        });
      } }))),
  };
}

async function reviewDetailView(id) {
  state.queue ??= await api.reviewQueue();
  const p = state.queue.find((x) => x.id === id);
  if (!p) {
    return messageView('This payment is not waiting for review',
      'It may already have been decided, or the address may be wrong.',
      h('div', { class: 'actions' }, linkButton('#/review', 'Back to held payments')));
  }
  return buildReviewDetail(p, []);
}

function buildReviewDetail(p, errors) {
  const redraw = (errs) => mountWithErrors((e) => buildReviewDetail(p, e), errs);
  const decisionErr = fieldError('decision', errors);
  const reasonErr = fieldError('reason', errors);
  const form = h('form', { novalidate: true });
  form.append(
    h('fieldset', { class: 'fieldset', 'aria-describedby': decisionErr ? 'decision-error' : false },
      h('legend', { class: 'legend' }, 'Your decision'),
      decisionErr && h('p', { id: 'decision-error', class: 'field-error' }, h('span', { class: 'visually-hidden' }, 'Error: '), decisionErr.message),
      [['APPROVE', 'Approve', 'Send the payment.'], ['REJECT', 'Reject', 'Do not send the payment.']].map(([value, label, hint], i) => {
        const cid = i === 0 ? 'decision' : `decision-${value.toLowerCase()}`;
        return h('div', { class: 'choice' },
          h('input', { type: 'radio', name: 'decision', id: cid, value, 'aria-describedby': `${cid}-hint` }),
          h('div', {}, h('label', { for: cid, class: 'label' }, label), h('p', { id: `${cid}-hint`, class: 'hint' }, hint)));
      })),
    h('div', { class: `field${reasonErr ? ' field--error' : ''}` },
      h('label', { for: 'reason', class: 'label' }, 'Reason'),
      h('p', { id: 'reason-hint', class: 'hint' }, 'Say why. It is saved in the payment\'s history. Up to 500 characters.'),
      reasonErr && h('p', { id: 'reason-error', class: 'field-error' }, h('span', { class: 'visually-hidden' }, 'Error: '), reasonErr.message),
      h('textarea', { id: 'reason', name: 'reason', class: 'input textarea', rows: '4', maxlength: '500',
        'aria-describedby': ['reason-hint', reasonErr && 'reason-error'].filter(Boolean).join(' '),
        'aria-invalid': reasonErr ? 'true' : false })),
    h('div', { class: 'actions' }, button('Save decision', { type: 'submit' }), linkButton('#/review', 'Back to held payments', 'secondary')));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const decision = form.querySelector('input[name="decision"]:checked')?.value ?? '';
    const reason = form.elements.reason.value.trim();
    const problems = [];
    if (!decision) problems.push({ field: 'decision', message: 'Choose approve or reject' });
    if (!reason) problems.push({ field: 'reason', message: 'Say why you made this decision' });
    if (problems.length) { redraw(problems); return; }
    const submit = event.submitter ?? form.querySelector('button[type="submit"]');
    await whileBusy(submit, 'Saving…', async () => {
      try {
        const decided = await api.decide(p.id, decision, reason);
        state.queue = state.queue.filter((x) => x.id !== p.id);
        const verb = decided.status === 'RELEASED' ? 'approved and sent' : 'rejected';
        mount(messageView('Decision saved', `You ${verb} the ${fmt.formatPence(decided.amountPence)} payment (${decided.reference}).`,
          h('div', { class: 'actions' }, linkButton('#/review', 'Back to held payments'))));
        announce('Decision saved.');
      } catch (e) {
        if (e.status === 401) return;
        redraw([{ message: copy.problemMessage(e) }]);
      }
    });
  });
  return {
    title: 'Review a held payment',
    node: h('div', {},
      errorSummary(errors),
      h('h1', {}, 'Review a held payment'),
      summary([
        ['Amount', fmt.formatPence(p.amountPence)],
        ['Reference', p.reference],
        ['Risk score', String(p.riskScore)],
        ['Held since', time(p.createdAt)],
        ['Payee reference', h('span', { class: 'mono' }, p.payeeId)],
      ]),
      h('h2', {}, 'Why it was held'),
      h('ul', {}, p.signals.map((s) => h('li', {}, copy.SIGNAL_LABELS[s] ?? s))),
      p.warning && h('p', { class: 'hint' }, `The customer was told: ${p.warning.message}`),
      historyDisclosure(p, (id) => api.reviewAudit(id)),
      form),
  };
}

// ---------------------------------------------------------------------------------------------------- start

/**
 * A link from scripts/demo-links.sh carries the token in the fragment. Take it, then remove it from the address bar
 * and history. A fragment is never sent to the server, and the page sends no Referer. This runs at load and on every
 * fragment change, so opening a link in a tab that already has the page open works too.
 */
function takeTokenFromHash() {
  const m = /^#token=([A-Za-z0-9_.-]+)$/.exec(location.hash);
  if (!m) return;
  history.replaceState(null, '', `${location.pathname}${location.search}#/`);
  const problem = trySignIn(m[1]);
  if (problem) state.notice = problem.message;
}

function boot() {
  takeTokenFromHash();
  window.addEventListener('hashchange', () => {
    takeTokenFromHash();
    // Only our own routes redraw the page. A link to a place on the page (#main, a field in an error summary) is
    // left to the browser, or the skip link would throw away the screen instead of moving focus.
    if (location.hash === '' || location.hash.startsWith('#/')) render();
  });
  render();
}

boot();
