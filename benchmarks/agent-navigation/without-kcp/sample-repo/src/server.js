const express = require("express");
const config = require("../config/settings");
const { authenticate } = require("./auth");
const { rateLimit } = require("./middleware");

const app = express();
app.use(express.json());
app.use(rateLimit);

// --- Public endpoint ---

app.get("/health", (req, res) => {
  res.json({ status: "ok", uptime: process.uptime() });
});

// --- Protected endpoints ---

app.get("/api/books", authenticate, (req, res) => {
  // Returns paginated book list
  const page = parseInt(req.query.page || "1", 10);
  const limit = parseInt(req.query.limit || "20", 10);
  res.json({ books: [], page, limit, total: 0 });
});

app.get("/api/books/:id", authenticate, (req, res) => {
  res.json({ id: req.params.id, title: "Not Found" });
});

app.post("/api/books", authenticate, (req, res) => {
  const { title, author, isbn } = req.body;
  if (!title || !author) {
    return res.status(400).json({ error: "title and author are required" });
  }
  res.status(201).json({ id: "new-id", title, author, isbn });
});

app.listen(config.port, () => {
  console.log(`Bookshelf API listening on port ${config.port}`);
});
