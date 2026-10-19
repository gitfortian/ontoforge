// Adapted from Yak Ops 85338827: apps/web/context/theme-context.tsx and theme helpers.
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

export type ThemeMode = 'light' | 'dark' | 'system';
type ResolvedTheme = Exclude<ThemeMode, 'system'>;
const STORAGE_KEY = 'ontoforge.ui.theme';
const isThemeMode = (value: unknown): value is ThemeMode =>
  value === 'light' || value === 'dark' || value === 'system';
const resolveTheme = (mode: ThemeMode, prefersDark: boolean): ResolvedTheme =>
  mode === 'system' ? (prefersDark ? 'dark' : 'light') : mode;

function storedTheme(): ThemeMode {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return isThemeMode(value) ? value : 'light';
  } catch {
    return 'light';
  }
}

type ThemeContextValue = {
  theme: ThemeMode;
  resolvedTheme: ResolvedTheme;
  persistent: boolean;
  setTheme: (mode: ThemeMode) => void;
};
const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeMode>(storedTheme);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() =>
    resolveTheme(storedTheme(), matchMedia('(prefers-color-scheme: dark)').matches));
  const [persistent, setPersistent] = useState(true);

  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)');
    const applyTheme = () => {
      const resolved = resolveTheme(theme, media.matches);
      document.documentElement.dataset.theme = resolved;
      document.documentElement.style.colorScheme = resolved;
      setResolvedTheme(resolved);
    };
    applyTheme();
    if (theme !== 'system') return;
    media.addEventListener('change', applyTheme);
    return () => media.removeEventListener('change', applyTheme);
  }, [theme]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY || event.key === null) {
        setThemeState(isThemeMode(event.newValue) ? event.newValue : 'light');
      }
    };
    addEventListener('storage', onStorage);
    return () => removeEventListener('storage', onStorage);
  }, []);

  const setTheme = (mode: ThemeMode) => {
    setThemeState(mode);
    try {
      localStorage.setItem(STORAGE_KEY, mode);
      setPersistent(true);
    } catch {
      setPersistent(false);
    }
  };
  return <ThemeContext.Provider value={{ theme, resolvedTheme, persistent, setTheme }}>
    {children}
  </ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext);
  if (!value) throw new Error('ThemeProvider is required');
  return value;
}
