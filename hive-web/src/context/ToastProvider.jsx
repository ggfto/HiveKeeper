import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'

const ToastContext = createContext(null)

/**
 * Lightweight, dependency-free toasts: a queue of transient notifications shown by the <Toaster/> (rendered
 * once near the app root). `toast(message, variant)` enqueues one and auto-dismisses it after 5s. Used for
 * action feedback (apply / discover / adopt / bulk ...) instead of a stray line of text per page.
 */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const seq = useRef(0)
  const timers = useRef(new Map())

  const dismiss = useCallback((id) => {
    setToasts((list) => list.filter((t) => t.id !== id))
    const handle = timers.current.get(id)
    if (handle) {
      clearTimeout(handle)
      timers.current.delete(id)
    }
  }, [])

  const toast = useCallback(
    (message, variant = 'default') => {
      if (!message) return null
      const id = (seq.current += 1)
      setToasts((list) => [...list, { id, message, variant }])
      timers.current.set(id, setTimeout(() => dismiss(id), 5000))
      return id
    },
    [dismiss],
  )

  useEffect(
    () => () => {
      timers.current.forEach((h) => clearTimeout(h))
      timers.current.clear()
    },
    [],
  )

  return <ToastContext.Provider value={{ toasts, toast, dismiss }}>{children}</ToastContext.Provider>
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within a ToastProvider')
  return ctx
}
