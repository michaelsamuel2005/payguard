# 7. A static web page, served by the API, with the token held in memory

Status: accepted, 14 October 2026

## Context

payguard had an API and no way for a customer to meet it. The page that matters most is the one where someone
is about to send money and the service thinks it might be a scam: the wording, the order of the screens and
whether a keyboard or a screen reader can get through it are part of whether the control works. A warning that
cannot be read, reached or understood protects nobody.

The page has to hold a bearer token, show text that other people typed (a payee name, a reference), and be
reachable by people using assistive technology.

## Decision

- **Plain HTML, CSS and JavaScript modules, served by the same Spring Boot application** from
  `src/main/resources/static`. No framework and no build step. The page is ten files (about 1,600 lines, half of it one
  script), and there is no dependency tree to patch.
- **The token lives in a JavaScript variable only.** Not in `localStorage`, `sessionStorage` or a cookie, and never
  in a URL path or query. For the demo, a person signs in by pasting a token, or by opening a link ending in
  `#token=...`. The part after `#` is never sent to a server, so it is not in any log; the page reads it, removes it
  from the address bar at once, and sends no referrer. A reload signs you out. That is deliberate.
- **Text goes in as text.** Every element is built by one small function (`dom.js`) that creates text nodes and
  refuses `on*` and `style` attributes. There is no `innerHTML` anywhere, and a test reads the source and fails if
  one appears.
- **A strict Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`): the page may load its own
  scripts and styles and call its own origin, nothing else; no inline script or style; no framing. The page is
  written to need no more than this, and the browser tests fail on any violation.
- **The page files are public, read-only and carry no data.** Only `GET` of `/`, `/index.html`, `/about.html`,
  `/accessibility.html` and `/assets/**` is open; everything else is still denied, and every API call still needs a
  signed token with the right scope. A page has to load before it can hold a token.
- **The page is held to WCAG 2.2 AA by tests, not by intent.** axe-core runs on about 28 states of the page in
  light and dark; keyboard-only journeys, reflow at 320 px and 200% text, text-spacing overrides and target size
  are checked in a real browser. `accessibility.html` says what was checked and, as plainly, what was not.

## Alternatives considered

- **React or Angular.** Better for a large product, and what a bank's front end would be. Here it would add a build,
  hundreds of dependencies and a bundle to patch, for ten screens. It would also hide the thing worth showing:
  that the behaviour is in the markup and the focus handling.
- **Server-rendered templates (Thymeleaf).** Would fit Spring and need no JavaScript, but the screens depend on
  state that lives between calls (the idempotency key, the chosen payee), and testing them would still need a
  browser.
- **Token in `sessionStorage`.** Survives a reload, but any script that runs on the page can read it, and a
  library added later would be a new way to lose it. The cost of memory-only is signing in again.
- **A cookie.** Would bring CSRF back (the API turns it off because it uses no cookies) for no benefit here.

## Consequences

- **Pasting a token is a demo device, not a sign-in.** In production the page would use the authorization-code flow
  with PKCE against an identity provider, or sit behind a backend-for-frontend that keeps the token in an
  `HttpOnly` cookie and the page never sees it. The `/v1` API would not change.
- **Memory-only is not XSS-proof.** A script running in the page can still read the variable. The defences are that
  one would have to get in (text-only DOM, strict CSP) and that the token is short-lived.
- **The CSP is part of the code.** Adding an inline script, a font from a CDN or an analytics tag fails the
  browser tests, which is the point.
- **The tests need a browser.** They run in CI against the Docker image (`ui-tests`), and the page's pure code is also
  unit-tested without one.
- **Tested in Chromium only**, with an automated accessibility checker and no screen reader or disabled users. That
  is written on the page itself.
