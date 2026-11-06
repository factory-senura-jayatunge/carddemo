import { createTheme, type CSSVariablesResolver } from '@mantine/core';
import { fontStack, palette, surface } from './tokens';

export const theme = createTheme({
  primaryColor: 'brand',
  primaryShade: 7,
  respectReducedMotion: true,
  colors: {
    brand: palette.brand,
    slate: palette.slate,
    gray: palette.slate,
    green: palette.green,
    red: palette.red,
    amber: palette.amber,
  },
  black: surface.text,
  white: '#ffffff',
  fontFamily: fontStack.sans,
  fontFamilyMonospace: fontStack.mono,
  focusRing: 'auto',
  cursorType: 'pointer',
  defaultRadius: 'md',
  radius: { xs: '4px', sm: '6px', md: '10px', lg: '14px', xl: '20px' },
  spacing: { xs: '8px', sm: '12px', md: '16px', lg: '24px', xl: '32px' },
  fontSizes: { xs: '0.8125rem', sm: '0.875rem', md: '1rem', lg: '1.125rem', xl: '1.25rem' },
  lineHeights: { xs: '1.45', sm: '1.5', md: '1.55', lg: '1.5', xl: '1.4' },
  headings: {
    fontFamily: fontStack.sans,
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '1.75rem', lineHeight: '1.25' },
      h2: { fontSize: '1.25rem', lineHeight: '1.35' },
      h3: { fontSize: '1.0625rem', lineHeight: '1.4' },
    },
  },
  shadows: {
    xs: '0 1px 2px rgba(22, 27, 38, 0.06)',
    sm: '0 1px 3px rgba(22, 27, 38, 0.08), 0 1px 2px rgba(22, 27, 38, 0.04)',
    md: '0 6px 16px -4px rgba(22, 27, 38, 0.10), 0 2px 4px rgba(22, 27, 38, 0.05)',
    lg: '0 18px 40px -12px rgba(22, 27, 38, 0.18)',
  },
  components: {
    Button: { defaultProps: { radius: 'md' } },
    TextInput: { defaultProps: { size: 'md' } },
    PasswordInput: { defaultProps: { size: 'md' } },
    Paper: { defaultProps: { radius: 'lg' } },
  },
});

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    '--cd-surface-page': surface.page,
    '--cd-surface-card': surface.card,
    '--cd-surface-sunken': surface.sunken,
    '--cd-border': surface.border,
    '--cd-border-strong': surface.borderStrong,
    '--cd-text': surface.text,
    '--cd-text-muted': surface.textMuted,
    '--cd-brand-ink': surface.brandInk,
  },
  light: {
    '--mantine-color-dimmed': surface.textMuted,
    '--mantine-color-body': surface.page,
    '--mantine-color-text': surface.text,
  },
  dark: {},
});
