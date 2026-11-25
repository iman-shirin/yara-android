import test from 'node:test';
import assert from 'node:assert/strict';
import { createFixedWindowLimiter } from '../shared/rate-limit.mjs';

test('fixed-window rate limit rejects excess calls and resets after the window', () => {
  const limiter = createFixedWindowLimiter({ limit: 2, windowMs: 1000 });
  assert.equal(limiter.consume('client', 0).allowed, true);
  assert.equal(limiter.consume('client', 1).allowed, true);
  const rejected = limiter.consume('client', 2);
  assert.equal(rejected.allowed, false);
  assert.equal(rejected.retryAfterSeconds, 1);
  assert.equal(limiter.consume('client', 1000).allowed, true);
});

test('rate-limit map refuses new keys when capacity is exhausted by live buckets', () => {
  const limiter = createFixedWindowLimiter({ limit: 5, windowMs: 1000, maxKeys: 1 });
  assert.equal(limiter.consume('first', 0).allowed, true);
  assert.equal(limiter.consume('second', 1).allowed, false);
  assert.equal(limiter.consume('second', 1000).allowed, true);
});

