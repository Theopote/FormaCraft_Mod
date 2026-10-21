"""Startup-only, additive JSON style packs. No code execution or builtin overrides."""
from copy import deepcopy
from functools import lru_cache
import json
import os
from pathlib import Path
import re

ASSETS = Path(__file__).resolve().parents[3]/'src/main/resources/assets/formacraft'
TABLES = {
    'style_profiles/style_profile_catalog_v1.json':'profiles',
    'style_profiles/style_identity_catalog_v1.json':'lexical_aliases',
    'palettes/palette_catalog_v1.json':'palettes',
    'structural_typologies/structural_typologies_v1.json':'typologies',
    'style_visual_cases/catalog_v1.json':'cases',
}
DIRECTORIES = {'style_genomes','culture_cards','proportion_cards','llmplan_examples','ornament_contracts'}


def _read(path):
    if path.stat().st_size > 262144:
        raise ValueError('JSON file exceeds 256 KiB')
    return json.loads(path.read_text(encoding='utf-8'))


def _builtin(relative):
    path=ASSETS/relative
    return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else None


def _merge(base, addition, table):
    out=deepcopy(base or {})
    destination=out.setdefault(table, [] if table in ('typologies','cases') else {})
    values=addition[table]
    existing={str(item['id']).casefold() for item in destination} if isinstance(destination,list) else {key.casefold() for key in destination}
    incoming=[item['id'] for item in values] if isinstance(values,list) else list(values)
    if len({key.casefold() for key in incoming})!=len(incoming) or existing.intersection(key.casefold() for key in incoming):
        raise ValueError('Duplicate or builtin asset ID')
    if isinstance(destination,list): destination.extend(deepcopy(values))
    else: destination.update(deepcopy(values))
    return out


def discover(directory):
    """Return a frozen-by-value snapshot; each invalid pack commits no assets."""
    try: root=Path(directory).expanduser().resolve()
    except (ValueError,OSError) as error: return {},[dict(id='',accepted=False,error='Invalid style pack root: '+str(error))]
    overlay={}; reports=[]; pack_ids=set()
    if not root.exists(): return overlay, reports
    if not root.is_dir(): return {}, [dict(id='',accepted=False,error='Style pack root is not a directory')]
    try: folders=sorted(root.iterdir(),key=lambda p:p.name)
    except OSError as error: return {},[dict(id='',accepted=False,error=str(error))]
    if len(folders)>32: return {}, [dict(id='',accepted=False,error='At most 32 pack directories are allowed')]
    for folder in folders:
        if not folder.is_dir(): continue
        pack_id=folder.name
        try:
            if folder.is_symlink() or folder.resolve().parent!=root: raise ValueError('Pack directory must stay inside root')
            manifest_path=folder/'style-pack.json'
            if manifest_path.is_symlink() or not manifest_path.resolve().is_relative_to(folder.resolve()): raise ValueError('Manifest path leaves pack')
            manifest=_read(manifest_path)
            pack_id=manifest.get('id','')
            if manifest.get('schema')!='formacraft.user_style_pack.v1' or not re.fullmatch(r'[a-z][a-z0-9_]{1,31}',pack_id):
                raise ValueError('Unsupported schema or invalid pack ID')
            if pack_id in pack_ids: raise ValueError('Duplicate pack ID')
            files=manifest.get('files')
            if not isinstance(files,list) or not 1<=len(files)<=128 or len(set(files))!=len(files):
                raise ValueError('Expected 1–128 unique JSON files')
            trial=deepcopy(overlay); total=0
            for relative in files:
                if not isinstance(relative,str) or not re.fullmatch(r'[a-z_]+/[A-Za-z0-9_]+\.json',relative):
                    raise ValueError('Invalid asset path')
                path=folder/relative
                if not path.resolve().is_relative_to(folder.resolve()) or path.is_symlink(): raise ValueError('Asset path leaves pack')
                total+=path.stat().st_size
                if total>4194304: raise ValueError('Pack exceeds 4 MiB')
                data=_read(path)
                if not isinstance(data,dict): raise ValueError('Asset must be an object')
                if relative in TABLES:
                    table=TABLES[relative]; values=data.get(table)
                    if table in ('typologies','cases'):
                        if not isinstance(values,list): raise ValueError('Catalog table must be an array')
                        ids=[item['id'] for item in values]
                    else:
                        if not isinstance(values,dict): raise ValueError('Catalog table must be an object')
                        ids=list(values)
                    if any(not isinstance(key,str) or not key.casefold().startswith(pack_id+'_') for key in ids):
                        raise ValueError('Asset IDs must use pack_id_ prefix')
                    if table=='typologies' and any(t.get('routingPolicy')!='compositional_only' or t.get('interpreterId') for t in values):
                        raise ValueError('User typologies must be compositional_only without native interpreters')
                    trial[relative]=_merge(trial.get(relative,_builtin(relative)),data,table)
                else:
                    if relative.split('/')[0] not in DIRECTORIES or not Path(relative).stem.casefold().startswith(pack_id+'_'):
                        raise ValueError('Unsupported asset kind or missing pack prefix')
                    if relative in trial or _builtin(relative) is not None: raise ValueError('Asset path collision')
                    if data.get('id')!=Path(relative).stem: raise ValueError('Asset ID must match filename')
                    trial[relative]=data
            _validate(trial)
            overlay=trial; pack_ids.add(pack_id)
            reports.append(dict(id=pack_id,accepted=True,files=len(files)))
        except Exception as error:
            reports.append(dict(id=pack_id,accepted=False,error=str(error)))
    return overlay,reports


def _validate(overlay):
    def catalog(path,table): return overlay.get(path,_builtin(path) or {}).get(table,{})
    profiles=catalog('style_profiles/style_profile_catalog_v1.json','profiles')
    palettes=catalog('palettes/palette_catalog_v1.json','palettes')
    types={t['id'] for t in catalog('structural_typologies/structural_typologies_v1.json','typologies')}
    identities=catalog('style_profiles/style_identity_catalog_v1.json','lexical_aliases')
    identity_data=overlay.get('style_profiles/style_identity_catalog_v1.json',_builtin('style_profiles/style_identity_catalog_v1.json'))
    aliases={alias.casefold():target for alias,target in identity_data.get('aliases',{}).items()}
    for style,names in identities.items():
        if not isinstance(names,list) or any(not isinstance(name,str) or not name.strip() for name in names): raise ValueError('Invalid style aliases')
        for name in [style,*names]:
            key=name.casefold()
            if key in aliases and aliases[key]!=style: raise ValueError('Style alias collision: '+name)
            aliases[key]=style
    builtin_identities=(_builtin('style_profiles/style_identity_catalog_v1.json') or {}).get('lexical_aliases',{})
    for style in set(identities)-set(builtin_identities):
        if style not in profiles and 'style_genomes/'+style+'.json' not in overlay: raise ValueError('Unknown identity profile/genome')
    for path,data in overlay.items():
        if path.startswith('style_genomes/'):
            roles=data.get('palette',{})
            required={'wall','roof','floor','window','foundation','trim','pillar','cap','door','roof_trim','stairs','roof_slab'}
            if set(roles)!=required or any(not isinstance(v,str) or not re.fullmatch(r'minecraft:[a-z0-9_]+',v) for v in roles.values()): raise ValueError('Genome requires twelve Minecraft material roles')
        elif path.startswith('culture_cards/'):
            if data.get('styleId') not in profiles: raise ValueError('Unknown culture style')
            if data.get('structuralTypologyId') not in types: raise ValueError('Unknown culture typology')
            for ref in data.get('exampleRefs',[]):
                if not isinstance(ref,str) or not re.fullmatch(r'[A-Za-z0-9_]+\.json',ref) or not (ASSETS/'assembly_examples'/ref).is_file():
                    raise ValueError('Missing/invalid assembly example reference')
            if data.get('patchCardId') and not re.fullmatch(r'[A-Za-z0-9_]+',data['patchCardId']): raise ValueError('Invalid patch card reference')
            for ref in data.get('llmPlanExampleRefs',[]):
                if not isinstance(ref,str) or not re.fullmatch(r'[A-Za-z0-9_]+\.json',ref): raise ValueError('Invalid plan reference')
                if 'llmplan_examples/'+ref not in overlay and not (ASSETS/'llmplan_examples'/ref).is_file(): raise ValueError('Missing plan example')
            ref=data.get('proportionCardId')
            if ref and (not isinstance(ref,str) or not re.fullmatch(r'[A-Za-z0-9_]+',ref)): raise ValueError('Invalid proportion reference')
            if ref and 'proportion_cards/'+ref+'.json' not in overlay and not (ASSETS/'proportion_cards'/f'{ref}.json').is_file(): raise ValueError('Missing proportion card')
        elif path.startswith('ornament_contracts/'):
            from .ornament_contract import validate_ornament_contract
            validate_ornament_contract(data)
            if data['application']=='standalone' and not data['report']['key'].startswith(data['id']+'_'):
                raise ValueError('User ornament report key must start with contract_id_')
        elif path.startswith('llmplan_examples/'):
            from ..models.llm_plan import validate_llm_plan_dict
            validate_llm_plan_dict(data.get('plan',data))
    for case in catalog('style_visual_cases/catalog_v1.json','cases'):
        if case['id'] in {value['id'] for value in (_builtin('style_visual_cases/catalog_v1.json') or {}).get('cases',[])}: continue
        if case.get('style_id') not in profiles or not isinstance(case.get('feature_groups'),dict) or len(case['feature_groups'])<2: raise ValueError('Invalid visual case')
        if any(not isinstance(terms,list) or not terms or any(not isinstance(term,str) or not term.strip() for term in terms) for terms in case['feature_groups'].values()): raise ValueError('Invalid visual feature terms')
        ref=case.get('plan_fixture','')
        if not re.fullmatch(r'[A-Za-z0-9_]+\.json',ref) or 'llmplan_examples/'+ref not in overlay: raise ValueError('Unknown visual plan example')
    for sid,profile in profiles.items():
        palette=(profile.get('defaults',{}).get('components') or {}).get('palette_id')
        if palette and palette not in palettes: raise ValueError('Unknown profile palette: '+palette)


@lru_cache(maxsize=1)
def snapshot():
    # Auto-launched backends receive an absolute path from Minecraft Settings.
    directory=os.getenv('FORMACRAFT_STYLE_PACKS_DIR','').strip()
    return discover(directory) if directory else ({},[])


def read_asset(path):
    path=Path(path)
    try: relative=path.resolve().relative_to(ASSETS.resolve()).as_posix()
    except ValueError: return json.loads(path.read_text(encoding='utf-8'))
    overlay,_=snapshot()
    return deepcopy(overlay[relative]) if relative in overlay else json.loads(path.read_text(encoding='utf-8'))


def asset_files(directory):
    directory=Path(directory)
    try: relative=directory.resolve().relative_to(ASSETS.resolve()).as_posix()+'/'
    except ValueError: return sorted(directory.glob('*.json'))
    overlay,_=snapshot()
    return sorted(set(directory.glob('*.json')) | {ASSETS/key for key in overlay if key.startswith(relative) and key.count('/')==1})


def asset_exists(path):
    try: relative=Path(path).resolve().relative_to(ASSETS.resolve()).as_posix()
    except ValueError: return Path(path).is_file()
    return relative in snapshot()[0] or Path(path).is_file()
