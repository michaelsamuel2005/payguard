// A tiny element builder. It only ever creates text nodes, never parses HTML, so a value that came from the
// server (a payee name, a reference) cannot become markup. There is no innerHTML anywhere in this app.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value === false || value == null) continue;
    if (/^on/i.test(name) || name.toLowerCase() === 'style') {
      throw new Error(`h(): "${name}" is not allowed (the page's Content-Security-Policy forbids it)`);
    }
    el.setAttribute(name === 'class' ? 'class' : name, value === true ? '' : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

/** Moves keyboard and screen-reader focus to an element that is not normally focusable. */
export function focusElement(el) {
  if (!el) return;
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
  el.focus({ preventScroll: false });
}
