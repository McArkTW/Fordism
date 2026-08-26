/**
 * The jsdom `matchMedia` returns a MediaQueryList without the listener API, and `Media.match`
 * subscribes to `change` on construction — so without this stub every component that reaches
 * Theming (i.e. most of them) fails to instantiate under test.
 */
const noop = (): void => undefined;

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: noop,
    removeEventListener: noop,
    dispatchEvent: () => false,
  }),
});
