import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Spring runs on :8080. Proxying /tracks (REST + the SSE stream) keeps the browser same-origin,
// so the backend needs no CORS config. The app's own pages also live under /tracks/..., so a
// browser navigation (Accept: text/html) is served the SPA instead of being proxied.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      "/tracks": {
        target: "http://localhost:8080",
        changeOrigin: true,
        bypass: (req) => (req.headers.accept?.includes("text/html") ? "/index.html" : undefined),
      },
    },
  },
});
