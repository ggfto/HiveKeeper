import React from 'react'
import { createRoot } from 'react-dom/client'
// The kit's stylesheet is imported from index.css (as `@layer kit`) so its unlayered utilities can't override
// our responsive utilities — see the cascade-layer note there.
import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
