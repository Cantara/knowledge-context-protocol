# Rate Limiting

- Default: 100 requests per minute per API key
- Burst: up to 20 requests per second
- On 429: retry after `Retry-After` header value (in seconds)
- Use exponential backoff for resilience
