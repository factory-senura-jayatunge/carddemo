/** Names arrive as stored, often in upper case ("LAWRENCE"); shown in title case. */
export function displayName(name: string): string {
  const trimmed = name.trim();
  if (trimmed !== trimmed.toUpperCase()) return trimmed;
  return trimmed.toLowerCase().replace(/(^|[\s'-])\p{L}/gu, (match) => match.toUpperCase());
}
