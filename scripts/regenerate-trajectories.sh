#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
compiler="${PROTOC:-protoc}"
if [[ "$("$compiler" --version)" != "libprotoc 25.3" ]]; then
  echo "Use protoc 3.25.3 (libprotoc 25.3). Set PROTOC to its path." >&2
  exit 1
fi
for schema in traj legacy_traj; do
  "$compiler" --proto_path=app/src/main/proto --java_out=app/src/main/java "app/src/main/proto/${schema}.proto"
done
python3 - <<'NORMALIZE'
from pathlib import Path
for name in ("Traj.java", "LegacyTraj.java"):
    path = Path("app/src/main/java/com/openpositioning/PositionMe") / name
    path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
NORMALIZE
