import type { MantineColorsTuple } from '@mantine/core';

/**
 * CardDemo design tokens. Every colour pair used for text meets WCAG 2.2 AA
 * (4.5:1) on the surface it is used on; the pairs are listed in
 * docs/design-tokens.md with their measured ratios.
 */
export const palette = {
  // Ink blue: brand, primary actions, focus.
  brand: [
    '#eef0ff', '#dfe3ff', '#bdc5fb', '#98a5f6', '#7886f1',
    '#5e6ded', '#4b59e0', '#3a47c4', '#2f3a9e', '#232c78',
  ],
  // Cool neutral: surfaces, borders, secondary text.
  slate: [
    '#f6f7f9', '#eef0f4', '#e1e5ec', '#cdd3de', '#a8b0bf',
    '#7b8496', '#566072', '#3f4758', '#283040', '#161b26',
  ],
  // Success (legacy green message).
  green: [
    '#e9f7ef', '#d3efdf', '#a6dfbf', '#74cd9c', '#4bbd7e',
    '#2fa868', '#238f57', '#1c7547', '#165c39', '#10432a',
  ],
  // Error (legacy red message).
  red: [
    '#fdecee', '#fad6da', '#f2a9b1', '#ea7a86', '#e05262',
    '#d33548', '#bd2639', '#9f1e2f', '#801a27', '#5f141d',
  ],
  // Notice (batch window, timeouts): amber, never used for errors.
  amber: [
    '#fff6e5', '#ffecc7', '#fdd78a', '#f9c04d', '#f2a91f',
    '#d88f0a', '#b07206', '#8a5806', '#6b4408', '#4c3007',
  ],
} satisfies Record<string, MantineColorsTuple>;

export const surface = {
  page: palette.slate[0],
  card: '#ffffff',
  sunken: palette.slate[1],
  border: palette.slate[2],
  borderStrong: palette.slate[3],
  text: palette.slate[9],
  textMuted: palette.slate[6],
  brandInk: palette.brand[9],
} as const;

export const severityTone = {
  error: { fg: palette.red[7], bg: palette.red[0], border: palette.red[2], label: 'Error' },
  success: { fg: palette.green[8], bg: palette.green[0], border: palette.green[2], label: 'Success' },
  info: { fg: palette.slate[8], bg: palette.slate[0], border: palette.slate[3], label: 'Information' },
  notice: { fg: palette.amber[8], bg: palette.amber[0], border: palette.amber[2], label: 'Notice' },
} as const;

export const fontStack = {
  sans: "'Inter Variable', Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
  mono: "ui-monospace, 'SF Mono', SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace",
} as const;
