"""Shared identity map. Variants retain identity until dedicated rules exist."""
import json
import re
from functools import lru_cache
from pathlib import Path
from .user_style_packs import read_asset

@lru_cache(maxsize=1)
def identity_catalog():
    path=Path(__file__).resolve().parents[3]/'src/main/resources/assets/formacraft/style_profiles/style_identity_catalog_v1.json'
    return read_asset(path)


def generic_style_pattern():
    """Literal catalog aliases, longest first; flexible whitespace for English names."""
    data = identity_catalog()
    markers = set(data.get('generic_style_markers', []))
    markers.update(data.get('aliases', {}))
    for aliases in data.get('lexical_aliases', {}).values():
        markers.update(aliases)
    patterns = [r'\s*'.join(re.escape(part) for part in marker.split())
                for marker in sorted(markers, key=lambda value: (-len(value), value))
                if marker.strip()]
    return re.compile('|'.join(patterns) if patterns else r'(?!)', re.IGNORECASE)


def _style_aliases():
    data = identity_catalog()
    aliases = {identity: {identity, *names} for identity, names in data.get('lexical_aliases', {}).items()}
    for name, identity in data.get('aliases', {}).items():
        aliases.setdefault(identity, set()).add(name)
    return aliases


def _alias_pattern(alias):
    pattern = r'\s*'.join(re.escape(part) for part in alias.split())
    if alias and alias[0].isascii() and alias[0].isalnum():
        pattern = r'(?<![a-z0-9_])' + pattern
    if alias and alias[-1].isascii() and alias[-1].isalnum():
        pattern += r'(?![a-z0-9_])'
    return pattern


def negated_style_ids(text):
    """Only explicit whole-style refusals; '不要哥特窗花' is a detail, not a style ban."""
    prefix = r'(?:不要|不用|不采用|不使用|别用|排除|拒绝|避免|\b(?:no|not|without|exclude)\s+)\s*'
    ending = r'(?:风格|建筑|式|\s+style|\s+architecture)?\s*(?:$|[，。；;,.!?！、]|但|而|改|要|\bbut\b|\binstead\b)'
    return {identity for identity, aliases in _style_aliases().items()
            if any(alias and re.search(prefix + _alias_pattern(alias) + ending, text, re.I) for alias in aliases)}


def matched_style_ids(text):
    rejected = negated_style_ids(text)
    return {identity for identity, aliases in _style_aliases().items() if identity not in rejected
            and any(alias and re.search(_alias_pattern(alias), text, re.I) for alias in aliases)}

def canonical_style_id(value):
    if not value: return value
    key=str(value).strip()
    data=identity_catalog()
    for alias,target in data['aliases'].items():
        if alias.casefold()==key.casefold(): return target
    for target,aliases in data['lexical_aliases'].items():
        if any(str(alias).casefold()==key.casefold() for alias in [target,*aliases]): return target
    for identity in [*data['variants'],*data['non_style_ids']]:
        if identity.casefold()==key.casefold(): return identity
    return key

def style_identity(value):
    requested=value
    canonical=canonical_style_id(value)
    data=identity_catalog()
    if canonical in data['variants']:
        return {'requested_id':requested,'identity_id':canonical,'status':'recognized_variant',**data['variants'][canonical]}
    if canonical in data['non_style_ids']:
        return {'requested_id':requested,'identity_id':canonical,'status':'non_style','profile_id':None}
    known=canonical in data['lexical_aliases']
    return {'requested_id':requested,'identity_id':canonical,'status':'canonical' if known else 'unknown',
            'profile_id':canonical if known else None}
