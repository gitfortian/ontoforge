import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './app/App';
import { ThemeProvider } from './shared/theme/ThemeProvider';
import './shared/ui/foundation.css';
import './app/shell.css';

createRoot(document.getElementById('root')!).render(<React.StrictMode><ThemeProvider><App /></ThemeProvider></React.StrictMode>);
