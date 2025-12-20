#!/usr/bin/env bash
set -euo pipefail

# Run backend (Clojure) and frontend (shadow-cljs) in separate terminals.
echo "Backend:  clj -M:dev"
echo "Frontend: npx shadow-cljs watch app"
