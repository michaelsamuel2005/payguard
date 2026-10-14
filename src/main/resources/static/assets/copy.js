// Every sentence a customer or reviewer reads that does not come from the API itself.
// Written in plain English, in short sentences. Warning *messages* come from the API (one source of truth);
// what lives here are the headings and the questions that help someone stop and think.
// ui-tests/unit/copy.test.mjs fails if the API adds a warning code, signal or status that has no text here.

/** The heading for each warning the API can send. Said first, so a screen-reader user hears the point at once. */
export const WARNING_HEADINGS = {
  SCAM_LANGUAGE: 'The reference looks like a common scam',
  NAME_MISMATCH: 'The name does not match this account',
  NAME_CHECK_UNAVAILABLE: 'We could not check who owns this account',
  RAPID_NEW_PAYEES: 'You have paid several new people recently',
  LARGE_NEW_PAYEE: 'This is a large payment to someone new',
  NAME_CLOSE_MATCH: 'The name is close, but not the same',
  UNUSUAL_AMOUNT: 'This is more than you usually pay',
  NEW_PAYEE: 'You have not paid this person before',
};

/** Questions that apply to every warning. Three is enough to remember. */
export const STOP_AND_THINK = [
  'Did someone contact you out of the blue, or tell you to act quickly?',
  'Did you get these bank details from someone you cannot check?',
  'Have you checked the request using a phone number or website you already trust?',
];

/** What the name check found. `title` is the page heading, so it says the result first. */
export const NAME_CHECK = {
  MATCH: {
    kind: 'success',
    label: 'Name matches',
    title: 'The name matches',
    text: 'The name you gave matches the name on the account.',
  },
  CLOSE_MATCH: {
    kind: 'warning',
    label: 'Name is close',
    title: 'The name is close, but not the same',
    text: 'The name you gave is close to the name on the account, but not the same.',
  },
  NO_MATCH: {
    kind: 'danger',
    label: 'Name does not match',
    title: 'The name does not match',
    text: 'The name you gave does not match the name on this account. For privacy, we do not show the real name.',
  },
  UNAVAILABLE: {
    kind: 'warning',
    label: 'Name not checked',
    title: 'We could not check the name',
    text: 'The check did not work just now. That does not mean something is wrong, but we cannot confirm who owns this account.',
  },
};

/** Where a payment is, in words a customer would use. */
export const PAYMENT_STATUS = {
  RELEASED: { kind: 'success', label: 'Sent' },
  AWAITING_CONFIRMATION: { kind: 'warning', label: 'Waiting for you to confirm' },
  HELD: { kind: 'warning', label: 'Held for a check' },
  REJECTED: { kind: 'danger', label: 'Stopped by a reviewer' },
  CANCELLED: { kind: 'neutral', label: 'Cancelled' },
};

/** What made the risk score go up, for the review team (never shown to customers). */
export const SIGNAL_LABELS = {
  NEW_PAYEE: 'First payment to this payee',
  NAME_CLOSE_MATCH: 'Name is close to the account name',
  NAME_NO_MATCH: 'Name does not match the account',
  NAME_CHECK_UNAVAILABLE: 'Name could not be checked',
  UNUSUAL_AMOUNT: 'Unusual amount for this customer',
  SCAM_LANGUAGE: 'Scam wording in the reference',
  RAPID_NEW_PAYEES: 'Several new payees in a short time',
};

export const AUDIT_ACTIONS = {
  INITIATED: 'Started',
  CONFIRMED: 'Confirmed by the customer',
  CANCELLED: 'Cancelled by the customer',
  APPROVED: 'Approved by a reviewer',
  REJECTED: 'Rejected by a reviewer',
};

/** A message for each way a call can fail, so nobody is shown a status code. */
export function problemMessage(error) {
  if (error.isNetwork) {
    return 'We could not reach the service. Check your connection and try again.';
  }
  switch (error.status) {
    case 429:
      return error.retryAfter
        ? `You are sending payments too quickly. Wait ${error.retryAfter} seconds and try again.`
        : 'You are sending payments too quickly. Wait a moment and try again.';
    case 403:
      return error.problem?.detail || 'Your sign-in does not allow this.';
    case 404:
      return error.problem?.detail || 'We could not find that.';
    case 400:
    case 409:
      return error.problem?.detail || 'The service could not accept that.';
    default:
      return 'Something went wrong on our side. Try again in a moment.';
  }
}

/**
 * When sending a payment fails with no clear answer (no response, or a server error), we cannot know whether the
 * payment was started. Saying "nothing was sent" would be a guess. Sending again is safe, because the same
 * Idempotency-Key is reused: the service answers with the payment it already made instead of making a second one.
 */
export const SEND_UNCERTAIN =
  'We could not confirm whether this payment was started. Select "Send payment" again. ' +
  'It is safe: the same details will not be sent twice.';
