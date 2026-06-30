import '@testing-library/jest-dom'

// jsdom is missing a few browser APIs that the kit's Radix-based components (Select, Dialog, ScrollArea)
// reach for. Provide minimal stand-ins so rendering them under test does not throw.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver || ResizeObserverStub

if (!window.matchMedia) {
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent() {
      return false
    },
  })
}

if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

// jsdom does not always provide a working localStorage; supply a minimal in-memory implementation so components
// that persist UI state (e.g. saved config templates) can read/write it under test.
if (!window.localStorage || typeof window.localStorage.clear !== 'function') {
  const store = new Map()
  window.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
    clear: () => store.clear(),
    key: (i) => [...store.keys()][i] ?? null,
    get length() {
      return store.size
    },
  }
}
