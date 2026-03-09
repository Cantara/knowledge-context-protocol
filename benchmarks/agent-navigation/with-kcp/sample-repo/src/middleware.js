const config = require("../config/settings");

/**
 * Simple in-memory rate limiter.
 *
 * Limits requests per IP address within a sliding window.
 * Configured via RATE_LIMIT_WINDOW (ms) and RATE_LIMIT_MAX env vars.
 * Returns 429 with Retry-After header when limit exceeded.
 */
const counters = new Map();

function rateLimit(req, res, next) {
  const ip = req.ip;
  const now = Date.now();
  const windowStart = now - config.rateLimit.windowMs;

  if (!counters.has(ip)) {
    counters.set(ip, []);
  }

  const timestamps = counters.get(ip).filter((t) => t > windowStart);
  counters.set(ip, timestamps);

  if (timestamps.length >= config.rateLimit.maxRequests) {
    const retryAfter = Math.ceil((timestamps[0] + config.rateLimit.windowMs - now) / 1000);
    res.set("Retry-After", String(retryAfter));
    return res.status(429).json({
      error: "Too Many Requests",
      retryAfter,
    });
  }

  timestamps.push(now);
  next();
}

module.exports = { rateLimit };
