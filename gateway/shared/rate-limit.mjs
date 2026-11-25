export function createFixedWindowLimiter({ limit, windowMs, maxKeys = 10_000 }) {
  if (!Number.isInteger(limit) || limit < 1 || !Number.isInteger(windowMs) || windowMs < 1) {
    throw new TypeError('Invalid rate-limit policy');
  }
  const buckets = new Map();

  return {
    consume(key, now = Date.now()) {
      const normalizedKey = String(key || 'unknown');
      let bucket = buckets.get(normalizedKey);
      if (!bucket || bucket.resetAt <= now) {
        if (buckets.size >= maxKeys) {
          for (const [existingKey, existing] of buckets) {
            if (existing.resetAt <= now) buckets.delete(existingKey);
          }
          if (buckets.size >= maxKeys) return { allowed: false, retryAfterSeconds: 1 };
        }
        bucket = { count: 0, resetAt: now + windowMs };
        buckets.set(normalizedKey, bucket);
      }
      bucket.count += 1;
      return {
        allowed: bucket.count <= limit,
        retryAfterSeconds: Math.max(1, Math.ceil((bucket.resetAt - now) / 1000))
      };
    }
  };
}

