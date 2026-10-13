import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ToastProvider } from "./lib/toast";
import { HomePage } from "./pages/HomePage";
import { WorkspacePage } from "./pages/WorkspacePage";
import "./index.css";

// Apply the stored theme before first paint so there's no flash of the wrong palette.
try {
  const stored = localStorage.getItem("theme");
  if (stored === "light" || stored === "dark") document.documentElement.dataset.theme = stored;
} catch {
  // storage unavailable
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5000, retry: (count, error) => !("status" in error && error.status === 404) && count < 2 },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/tracks/:trackId" element={<WorkspacePage />} />
            <Route path="/tracks/:trackId/topics/:topicId" element={<WorkspacePage />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
