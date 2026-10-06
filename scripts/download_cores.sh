#!/usr/bin/env bash
# Compatibility entry point: builds the pinned source cores.
set -euo pipefail
python3 "$(dirname "$0")/build_cores.py"
