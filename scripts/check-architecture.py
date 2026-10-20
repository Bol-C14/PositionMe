#!/usr/bin/env python3
"""Check M1 dependency boundaries without requiring Android tooling."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1] / "app/src/main/java/com/openpositioning/PositionMe"
violations = []
for source in (root / "domain").rglob("*.java"):
    for dependency in re.findall(r"^import\s+([^;]+);", source.read_text(), re.M):
        if dependency.startswith(("android.", "androidx.", "com.google.android.", "org.json.",
                                  "com.openpositioning.PositionMe.presentation.",
                                  "com.openpositioning.PositionMe.sensors.",
                                  "com.openpositioning.PositionMe.data.")):
            violations.append(f"{source.relative_to(root)} imports {dependency}")
for source in (root / "sensors").rglob("*.java"):
    for dependency in re.findall(r"^import\s+([^;]+);", source.read_text(), re.M):
        if dependency.startswith("com.openpositioning.PositionMe.presentation.") or dependency.endswith(".PathView"):
            violations.append(f"{source.relative_to(root)} imports {dependency}")
if violations:
    print("Architecture boundary violations:\n" + "\n".join(violations))
    sys.exit(1)
print("Domain/platform and sensor/presentation boundaries: PASS")
