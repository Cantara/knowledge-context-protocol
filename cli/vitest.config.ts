import { defineConfig } from "vitest/config";
import { resolve } from "node:path";

// Redirect shared-core imports to shared/src/ so vitest can resolve them
// without the files physically existing in cli/src/. This mirrors the
// TypeScript rootDirs virtual merge used during tsc compilation.
const SHARED = resolve(__dirname, "../shared/src");

export default defineConfig({
  resolve: {
    alias: [
      {
        find: /^(.*\/|)model\.js$/,
        replacement: resolve(SHARED, "model.ts"),
      },
      {
        find: /^(.*\/|)parser\.js$/,
        replacement: resolve(SHARED, "parser.ts"),
      },
      {
        find: /^(.*\/|)validator\.js$/,
        replacement: resolve(SHARED, "validator.ts"),
      },
    ],
  },
  test: {
    environment: "node",
    globals: false,
  },
});
