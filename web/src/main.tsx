import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import { initializeKeycloak } from './app/keycloak'
import './index.css'

const root = createRoot(document.getElementById('root')!)

initializeKeycloak()
  .then(() => root.render(<StrictMode><App /></StrictMode>))
  .catch(() => root.render(<StrictMode><App initializationError /></StrictMode>))
