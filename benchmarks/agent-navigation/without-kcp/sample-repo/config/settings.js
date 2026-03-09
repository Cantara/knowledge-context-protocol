// Application configuration — loaded from environment variables
module.exports = {
  port: parseInt(process.env.API_PORT || "3000", 10),
  db: {
    host: process.env.DB_HOST || "localhost",
    port: parseInt(process.env.DB_PORT || "5432", 10),
    name: process.env.DB_NAME || "bookshelf",
    user: process.env.DB_USER || "app",
    password: process.env.DB_PASSWORD,  // required — no default
  },
  auth: {
    jwtSecret: process.env.JWT_SECRET,  // required — no default
    tokenExpiry: process.env.TOKEN_EXPIRY || "24h",
  },
  rateLimit: {
    windowMs: parseInt(process.env.RATE_LIMIT_WINDOW || "60000", 10),
    maxRequests: parseInt(process.env.RATE_LIMIT_MAX || "100", 10),
  },
};
