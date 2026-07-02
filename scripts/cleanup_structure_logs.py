#!/usr/bin/env python3
"""Batch-replace swallowed catches in generation/structure and delegate duplicate parsers."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STRUCTURE = ROOT / "src/main/java/com/formacraft/common/generation/structure"

PARSER_IMPORT = "import com.formacraft.common.generation.structure.util.StructureSpecParsers;\n"
LOG_IMPORT = "import com.formacraft.common.logging.FcaLog;\n"

RE_THROWABLE_EMPTY = re.compile(r"\} catch \(Throwable ignored\) \{\s*\}", re.MULTILINE)
RE_EXCEPTION_EMPTY = re.compile(r"\} catch \(Exception ignored\) \{\s*\}", re.MULTILINE)
RE_NFE_EMPTY = re.compile(r"\} catch \(NumberFormatException ignored\) \{\s*\}", re.MULTILINE)
RE_EXCEPTION_EMPTY_BLOCK = re.compile(r"\} catch \(Exception ignored\) \{\s*\n\s*\}", re.MULTILINE)


def ensure_imports(text: str, need_log: bool, need_parser: bool) -> str:
    if need_log and "import com.formacraft.common.logging.FcaLog" not in text:
        insert_at = text.find("\nimport ")
        if insert_at == -1:
            insert_at = text.find("\npublic ")
        text = text[:insert_at] + "\n" + LOG_IMPORT + text[insert_at:]
    if need_parser and "StructureSpecParsers" not in text:
        insert_at = text.find("\nimport ")
        if insert_at == -1:
            insert_at = text.find("\npublic ")
        text = text[:insert_at] + "\n" + PARSER_IMPORT + text[insert_at:]
    return text


def add_log_field(text: str, class_name: str) -> str:
    marker = f'FcaLog.of("{class_name}")'
    if marker in text:
        return text
    m = re.search(rf"(public (?:final )?(?:class|enum) {class_name}[^{{]*\{{)", text)
    if not m:
        return text
    insert_pos = m.end()
    field = f"\n\n    private static final FcaLog LOG = FcaLog.of(\"{class_name}\");"
    return text[:insert_pos] + field + text[insert_pos:]


def replace_get_int_extra(text: str) -> tuple[str, bool]:
    pattern = re.compile(
        r"private static int getIntExtra\(BuildingSpec spec, String key, int def\) \{.*?\n    \}",
        re.DOTALL,
    )
    replacement = (
        "private static int getIntExtra(BuildingSpec spec, String key, int def) {\n"
        "        return StructureSpecParsers.extraInt(spec, key, def);\n"
        "    }"
    )
    new_text, n = pattern.subn(replacement, text)
    return new_text, n > 0


def replace_get_int_map(text: str) -> tuple[str, bool]:
    pattern = re.compile(
        r"private static int getInt\(Map<String, Object> extra, String key, int def\) \{.*?\n    \}",
        re.DOTALL,
    )
    replacement = (
        "private static int getInt(Map<String, Object> extra, String key, int def) {\n"
        "        return StructureSpecParsers.mapInt(extra, key, def);\n"
        "    }"
    )
    new_text, n = pattern.subn(replacement, text)
    return new_text, n > 0


def replace_blueprint_get_int(text: str) -> tuple[str, bool]:
    pattern = re.compile(
        r"private static int getInt\(Object v, int def\) \{.*?\n    \}",
        re.DOTALL,
    )
    replacement = (
        "private static int getInt(Object v, int def) {\n"
        "        return StructureSpecParsers.intValue(v, def);\n"
        "    }"
    )
    new_text, n = pattern.subn(replacement, text)
    return new_text, n > 0


def process_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    class_name = path.stem
    text = original
    used_parser = False

    text, p = replace_get_int_extra(text)
    used_parser = used_parser or p
    text, p = replace_get_int_map(text)
    used_parser = used_parser or p
    text, p = replace_blueprint_get_int(text)
    used_parser = used_parser or p

    need_log = False

    if RE_THROWABLE_EMPTY.search(text):
        need_log = True
        text = RE_THROWABLE_EMPTY.sub(
            '} catch (Throwable t) { LOG.debug("best-effort step failed", t); }', text
        )
    if RE_EXCEPTION_EMPTY.search(text):
        need_log = True
        text = RE_EXCEPTION_EMPTY.sub(
            '} catch (Exception e) { LOG.debug("best-effort step failed", e); }', text
        )
    if RE_NFE_EMPTY.search(text):
        need_log = True
        text = RE_NFE_EMPTY.sub(
            '} catch (NumberFormatException e) { LOG.debug("parse number failed", e); }', text
        )
    if RE_EXCEPTION_EMPTY_BLOCK.search(text):
        need_log = True
        text = RE_EXCEPTION_EMPTY_BLOCK.sub(
            '} catch (Exception e) {\n            LOG.debug("best-effort step failed", e);\n        }',
            text,
        )

    if need_log:
        text = ensure_imports(text, need_log=True, need_parser=used_parser)
        text = add_log_field(text, class_name)
    elif used_parser:
        text = ensure_imports(text, need_log=False, need_parser=True)

    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        return True
    return False


def main() -> None:
    changed = 0
    for path in sorted(STRUCTURE.rglob("*.java")):
        if path.name == "StructureSpecParsers.java":
            continue
        if process_file(path):
            changed += 1
            print(f"updated: {path.relative_to(ROOT)}")
    print(f"done: {changed} files")


if __name__ == "__main__":
    main()
