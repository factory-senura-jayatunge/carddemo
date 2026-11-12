import { describe, expect, it } from 'vitest';
import { summaryAddress, summaryName } from './authorizationDisplay';

describe('authorization summary display cuts (DEF-AUSM-004)', () => {
  it('cuts the name at 25 characters and keeps its spaces', () => {
    expect(summaryName('Immanuel M Kessler')).toBe('Immanuel M Kessler');
    expect(summaryName('Maximiliana   Vandenberghe-Oppenheimer')).toBe('Maximiliana   Vandenbergh');
    expect(summaryName('Maximiliana   Vandenberghe-Oppenheimer')).toHaveLength(25);
  });

  it('cuts each address value at its first double space, then the line at 25', () => {
    expect(summaryAddress('618 Deshaun Route,Apt. 802')).toBe('618 Deshaun Route,Apt. 80');
    expect(summaryAddress('12  Long Acre Road,Unit 4')).toBe('12,Unit 4');
    expect(summaryAddress('Saint  Petersburg Beach,FL,33706')).toBe('Saint,FL,33706');
    expect(summaryAddress('New Gladys,GA,19852')).toBe('New Gladys,GA,19852');
    expect(summaryAddress(',')).toBe(',');
  });

  it('counts characters, not UTF-16 units', () => {
    expect(summaryName(`${'😀'.repeat(30)}`)).toBe('😀'.repeat(25));
  });
});
