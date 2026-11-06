import '@fontsource-variable/inter';
import '@mantine/core/styles.css';
import './theme/global.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { App } from './App';
import { routes } from './router';

async function start() {
  // Mock mode answers the API in the browser; the import is dropped from
  // every other build.
  if (import.meta.env.MODE === 'mock') {
    const { installMock } = await import('./mocks/install');
    installMock();
  }
  const root = document.getElementById('root');
  if (!root) throw new Error('#root missing in index.html');
  createRoot(root).render(
    <StrictMode>
      <App router={createBrowserRouter(routes)} />
    </StrictMode>,
  );
}

void start();
