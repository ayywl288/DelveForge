import { test } from 'node:test';
import assert from 'node:assert/strict';
import { increment, isDue } from '../src/counter.js';
test('advances the due-date offset', () => assert.equal(increment(2), 3));
test('recognizes overdue and upcoming offsets', () => {
    assert.equal(isDue(-1), true);
    assert.equal(isDue(0), true);
    assert.equal(isDue(3), false);
});
