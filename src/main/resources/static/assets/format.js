// Pure helpers: no DOM and no network, so they can be unit-tested in Node (ui-tests/unit).
// The rules mirror the API's own (api/openapi.yaml and the domain classes). The server checks every value again
// and has the last word; these checks exist to give the person a clear message before they send anything.

/** The API's reference rule: 1 to 18 letters, digits, spaces or - . / & */
export const REFERENCE_PATTERN = /^[A-Za-z0-9 ./&-]{1,18}$/;

/** What the Idempotency-Key header accepts. */
export const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

/**
 * "25", "25.5", "£1,200.00" -> pence, or null if the text is not a plain amount of pounds and pence.
 * Whole numbers only: no floating point is used, so 0.1 + 0.2 style errors cannot happen.
 */
export function parsePounds(text) {
  const t = String(text ?? '').trim().replace(/^£\s*/, '');
  const m = /^(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?$/.exec(t);
  if (!m) return null;
  const pounds = Number(m[1].replace(/,/g, ''));
  const pence = m[2] === undefined ? 0 : Number(m[2].padEnd(2, '0'));
  const total = pounds * 100 + pence;
  return Number.isSafeInteger(total) ? total : null;
}

/** 150000 -> "£1,500.00". */
export function formatPence(pence) {
  const p = Math.abs(Math.trunc(pence));
  const pounds = Math.floor(p / 100);
  return `${pence < 0 ? '-' : ''}£${pounds.toLocaleString('en-GB')}.${String(p % 100).padStart(2, '0')}`;
}

/** A sort code is six digits, typed with or without hyphens or spaces. Returns the six digits, or null. */
export function parseSortCode(text) {
  const digits = String(text ?? '').replace(/[-\s]/g, '');
  return /^\d{6}$/.test(digits) ? digits : null;
}

export function formatSortCode(digits) {
  return `${digits.slice(0, 2)}-${digits.slice(2, 4)}-${digits.slice(4)}`;
}

/** An account number is eight digits, typed with or without spaces. Returns the eight digits, or null. */
export function parseAccountNumber(text) {
  const digits = String(text ?? '').replace(/\s/g, '');
  return /^\d{8}$/.test(digits) ? digits : null;
}

/** 1 to 70 characters, at least one of them a letter (the API's rule). */
export function isValidPayeeName(text) {
  const t = String(text ?? '').trim();
  return t.length >= 1 && t.length <= 70 && /\p{L}/u.test(t);
}

export function isValidReference(text) {
  return REFERENCE_PATTERN.test(String(text ?? '').trim());
}

/** "****5678" -> "5678". Read aloud, "ending 5678" is kinder than four asterisks. */
export function lastFour(masked) {
  return String(masked ?? '').replace(/\D/g, '').slice(-4);
}

/**
 * Reads a JWT's payload for display only (who is signed in, which screens to show). It does NOT verify the
 * signature: the server does that on every call, and nothing here is a security decision.
 * Returns {sub, scopes, exp} or null if the text is not a three-part token with a JSON payload.
 */
export function readToken(text) {
  const parts = String(text ?? '').trim().split('.');
  if (parts.length !== 3 || parts.some((p) => !/^[A-Za-z0-9_-]+$/.test(p))) return null;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(parts[1].length / 4) * 4, '=');
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    if (typeof claims.sub !== 'string' || claims.sub === '') return null;
    const raw = claims.scope ?? claims.scp ?? '';
    const scopes = Array.isArray(raw) ? raw.map(String) : String(raw).split(/\s+/).filter(Boolean);
    return { sub: claims.sub, scopes, exp: typeof claims.exp === 'number' ? claims.exp : null };
  } catch {
    return null;
  }
}

/** A fresh Idempotency-Key. randomUUID needs a secure context, so fall back to getRandomValues. */
export function newIdempotencyKey(cryptoImpl = globalThis.crypto) {
  if (typeof cryptoImpl?.randomUUID === 'function') return cryptoImpl.randomUUID();
  const b = cryptoImpl.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Which form field a server message is about, so the error summary can link to it. The API's messages are
 * written for the caller ("an amount is between 1p and ..."), so matching on a keyword is enough here.
 */
export function fieldForProblem(detail) {
  const d = String(detail ?? '').toLowerCase();
  if (d.includes('amount')) return 'amount';
  if (d.includes('reference')) return 'reference';
  if (d.includes('sort code')) return 'sort-code';
  if (d.includes('account number')) return 'account-number';
  if (d.includes('payee name')) return 'payee-name';
  return null;
}
