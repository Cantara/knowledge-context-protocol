# Test Queries (Without KCP)

Ask an AI agent these 5 questions about the sample repo. Record how many
files it reads and how long it takes to find the answer.

## Query 1: Authentication
> "How do I authenticate with this API?"

**Expected answer:** JWT bearer token in Authorization header.
**Where it lives:** `src/auth.js`

## Query 2: Environment Variables
> "What environment variables does this project need?"

**Expected answer:** DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, JWT_SECRET, TOKEN_EXPIRY, API_PORT, RATE_LIMIT_WINDOW, RATE_LIMIT_MAX
**Where it lives:** `config/settings.js`

## Query 3: Rate Limiting
> "Where is the rate limiting configured and what are the defaults?"

**Expected answer:** `src/middleware.js`, 100 requests per 60-second window, returns 429 with Retry-After.
**Where it lives:** `src/middleware.js` + `config/settings.js`

## Query 4: Creating a Book
> "How do I create a new book via the API?"

**Expected answer:** POST /api/books with JSON body containing title (required), author (required), isbn (optional). Requires authentication.
**Where it lives:** `src/server.js`

## Query 5: Error Handling
> "What happens when I send a request without authentication?"

**Expected answer:** 401 response with `{ error: "Unauthorized" }`.
**Where it lives:** `src/auth.js`
