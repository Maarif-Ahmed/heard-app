#!/usr/bin/env python3
"""
Train a lightweight token-correction model from TSV pairs.

Input format (tab-separated):
observed_token<TAB>canonical_token

Example:
microfone    microphone

This script outputs a JSON mapping of observed->canonical, selecting the most
frequent canonical target for each observed token.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("ai/training/seed_pairs.tsv"),
        help="Path to TSV file with observed/canonical pairs",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("ai/training/token_model.json"),
        help="Path to output JSON model file",
    )
    return parser.parse_args()


def train_model(input_path: Path) -> dict[str, str]:
    if not input_path.exists():
        raise FileNotFoundError(f"Input dataset not found: {input_path}")

    votes: dict[str, Counter[str]] = defaultdict(Counter)
    with input_path.open("r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            parts = stripped.split("\t")
            if len(parts) != 2:
                continue
            observed = parts[0].strip().lower()
            canonical = parts[1].strip().lower()
            if not observed or not canonical:
                continue
            votes[observed][canonical] += 1

    model: dict[str, str] = {}
    for observed, counter in votes.items():
        canonical, _count = counter.most_common(1)[0]
        model[observed] = canonical

    return dict(sorted(model.items()))


def main() -> None:
    args = parse_args()
    model = train_model(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=True, indent=2, sort_keys=True)
        f.write("\n")
    print(f"Wrote {len(model)} token rules to {args.output}")


if __name__ == "__main__":
    main()
