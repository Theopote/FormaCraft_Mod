"""Shared identity map. Variants retain identity until dedicated rules exist."""
import json
from functools import lru_cache
from pathlib import Path

@lru_cache(maxsize=1)
def identity_catalog():
    path=Path(__file__).resolve().parents[3]/'src/main/resources/assets/formacraft/style_profiles/style_identity_catalog_v1.json'
    return json.loads(path.read_text(encoding='utf-8'))

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
