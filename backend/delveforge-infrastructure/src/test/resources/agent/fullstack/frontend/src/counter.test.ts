import { expect, test } from 'vitest';
import { next } from './counter';
test('user increments counter', () => { expect(next(1)).toBe(2); });
