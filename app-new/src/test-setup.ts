// Vitest runs in jsdom, which implements neither matchMedia nor scrollIntoView. Theming reads
// matchMedia at construction, so without this stub nothing that touches Theme can instantiate.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => undefined);
