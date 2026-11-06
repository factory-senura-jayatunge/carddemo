interface BrandMarkProps {
  size?: number;
}

/** The CardDemo mark: a card outline on the brand ink square. Decorative. */
export function BrandMark({ size = 32 }: BrandMarkProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="8" fill="var(--mantine-color-brand-7)" />
      <rect x="6" y="10" width="20" height="13" rx="2.5" fill="none" stroke="#fff" strokeWidth="2" />
      <path d="M6 14.5h20" stroke="#fff" strokeWidth="2" />
      <path d="M10 19h5" stroke="var(--mantine-color-brand-2)" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
