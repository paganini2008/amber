// Variable weight axis: body text at 450 reads less thin than 400 (static 400/500/600 stay as fallback)
import '@fontsource-variable/ibm-plex-sans/wght.css'
import '@fontsource-variable/ibm-plex-sans/wght-italic.css'
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/400-italic.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import './styles.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { useStore } from './store'

// Development only: lets scripted UI checks (over the DevTools protocol) drive the app state.
if (import.meta.env.DEV) (window as unknown as { __amberDev: unknown }).__amberDev = { store: useStore }

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
)
