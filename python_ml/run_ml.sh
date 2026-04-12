#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f venv/bin/activate ]]; then
  echo "Creating venv under $(pwd)/venv ..."
  python3 -m venv venv
fi
# shellcheck source=/dev/null
source venv/bin/activate

pip install -r requirements.txt

exec uvicorn app:app --host 0.0.0.0 --port 8001 --workers 4
