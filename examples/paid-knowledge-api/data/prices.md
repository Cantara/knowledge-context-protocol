# Real-time asset prices

> Metered: each fetch of this unit costs **0.002 USDC** via x402 (settled on `base` or `ethereum`),
> or is included in a subscription. An agent reads the `payment` block and either pays per request
> or presents a subscription token — before fetching, never after a surprise 402.

| Asset | Price (USD) | As of |
|-------|-------------|-------|
| BTC | 71,240.00 | hourly snapshot |
| ETH | 3,910.50 | hourly snapshot |
| USDC | 1.00 | peg |

Live values are served by the API; this file documents the shape and the cost model.
