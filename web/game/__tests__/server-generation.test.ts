// The one numeric → canonical string boundary for the cancellation journal generation.
import { expect, test } from 'vitest';
import { normalizeServerGeneration } from '../lib/command-flow/server-generation';

test.each([
  [0, '0'],
  [-0, '0'],
  [7, '7'],
  [8, '8'],
  [Number.MAX_SAFE_INTEGER, String(Number.MAX_SAFE_INTEGER)],
])('a safe integer >= 0 becomes its decimal string: %s', (value, expected) => {
  expect(normalizeServerGeneration(value)).toBe(expected);
});

test.each([
  ['null', null],
  ['undefined', undefined],
  ['fraction', 7.5],
  ['negative', -1],
  ['Infinity', Number.POSITIVE_INFINITY],
  ['-Infinity', Number.NEGATIVE_INFINITY],
  ['NaN', Number.NaN],
  ['unsafe integer', Number.MAX_SAFE_INTEGER + 1],
  ['numeric string', '7'],
  ['empty string', ''],
  ['boolean', true],
  ['object', { generation: 7 }],
  ['array', [7]],
  ['bigint', BigInt(7)],
])('anything else is no generation (null), never a serialized guess: %s', (_, value) => {
  expect(normalizeServerGeneration(value)).toBeNull();
});
