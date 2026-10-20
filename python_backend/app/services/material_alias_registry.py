"""Shared authored material names. Runtime block validation remains authoritative."""
import json
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=1)
def material_aliases() -> dict[str, str]:
    path = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/formacraft/palettes/material_alias_catalog.json'
    return json.loads(path.read_text(encoding='utf-8'))['aliases']
