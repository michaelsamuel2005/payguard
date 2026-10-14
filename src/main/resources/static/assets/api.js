// The only file that talks to the network. Every call carries the bearer token; errors become ApiError.

export class ApiError extends Error {
  /** @param status HTTP status, or 0 when no response arrived (offline, timed out). */
  constructor(status, problem = {}, retryAfter = null) {
    super(problem.detail || problem.title || (status === 0 ? 'no response' : `HTTP ${status}`));
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.retryAfter = retryAfter;
  }
  get isNetwork() { return this.status === 0; }
}

export function createApi({ getToken, onUnauthorised, fetchImpl = (...a) => fetch(...a), timeoutMs = 20000 }) {
  async function call(method, path, { body, headers = {} } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let response;
    try {
      response = await fetchImpl(path, {
        method,
        signal: controller.signal,
        headers: {
          Accept: 'application/json, application/problem+json',
          Authorization: `Bearer ${getToken()}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...headers,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        cache: 'no-store',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
      });
    } catch {
      throw new ApiError(0);
    } finally {
      clearTimeout(timer);
    }
    const type = response.headers.get('content-type') || '';
    const data = type.includes('json') ? await response.json().catch(() => ({})) : {};
    if (response.status === 401) {
      onUnauthorised();
      throw new ApiError(401, data);
    }
    if (!response.ok) {
      const wait = Number(response.headers.get('retry-after'));
      throw new ApiError(response.status, data, Number.isFinite(wait) && wait > 0 ? wait : null);
    }
    return { status: response.status, data, replayed: response.headers.get('idempotent-replayed') === 'true' };
  }

  const id = encodeURIComponent;
  return {
    listPayees: () => call('GET', '/v1/payees').then((r) => r.data),
    registerPayee: (body) => call('POST', '/v1/payees', { body }).then((r) => r.data),
    initiatePayment: (key, body) => call('POST', '/v1/payments', { body, headers: { 'Idempotency-Key': key } }),
    getPayment: (paymentId) => call('GET', `/v1/payments/${id(paymentId)}`).then((r) => r.data),
    paymentAudit: (paymentId) => call('GET', `/v1/payments/${id(paymentId)}/audit`).then((r) => r.data),
    confirmPayment: (paymentId, code) =>
      call('POST', `/v1/payments/${id(paymentId)}/confirm`, { body: { acknowledgedWarning: code } }).then((r) => r.data),
    cancelPayment: (paymentId) => call('POST', `/v1/payments/${id(paymentId)}/cancel`).then((r) => r.data),
    reviewQueue: (limit = 50) => call('GET', `/v1/reviews?limit=${limit}`).then((r) => r.data),
    decide: (paymentId, decision, reason) =>
      call('POST', `/v1/reviews/${id(paymentId)}`, { body: { decision, reason } }).then((r) => r.data),
    reviewAudit: (paymentId) => call('GET', `/v1/reviews/${id(paymentId)}/audit`).then((r) => r.data),
  };
}
