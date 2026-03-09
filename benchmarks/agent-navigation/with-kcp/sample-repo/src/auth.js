const jwt = require("jsonwebtoken");
const config = require("../config/settings");

/**
 * Express middleware: validates JWT bearer token.
 *
 * Expects header: Authorization: Bearer <token>
 * Token must be signed with JWT_SECRET env var.
 * On failure: 401 with { error: "Unauthorized" }.
 */
function authenticate(req, res, next) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  const token = header.slice(7);
  try {
    const payload = jwt.verify(token, config.auth.jwtSecret);
    req.user = payload;
    next();
  } catch (err) {
    res.status(401).json({ error: "Unauthorized" });
  }
}

/**
 * Generate a JWT token for a user.
 * Used by login endpoint (not shown in this demo).
 */
function generateToken(userId, role) {
  return jwt.sign(
    { sub: userId, role },
    config.auth.jwtSecret,
    { expiresIn: config.auth.tokenExpiry }
  );
}

module.exports = { authenticate, generateToken };
