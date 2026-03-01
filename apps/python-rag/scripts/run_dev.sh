#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload

