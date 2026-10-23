"""Opt-in FBD prompting from the same primitive signatures used by Java."""
import json
import os
import re
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/formacraft/fbd'


def requests_fbd(text: str) -> bool:
    """Use only the current human message, never the assembled schema or history."""
    text = text or ''
    if re.search(r'(?i)(?:不要(?:使用|用)?|不使用|不用|禁用|关闭)\s*(?:实验性\s*)?FBD|do not use FBD', text):
        return False
    return bool(re.search(r'(?i)(?<![a-z0-9_])fbd(?![a-z0-9_])|fbd_program', text))


def is_fbd_only_plan(plan: dict) -> bool:
    return has_fbd_program(plan) and all(
        isinstance(c, dict) and c.get('component_type') == 'ASSEMBLY'
        and isinstance(c.get('params'), dict) and 'fbd_program' in c['params']
        for c in plan['components'])


def fbd_prompt_block(user_text: str = '') -> str:
    if not requests_fbd(user_text) and (os.getenv('LLMPLAN_FBD') or 'off').strip().lower() not in ('1', 'true', 'on'):
        return ''
    signatures = json.loads((_ROOT / 'primitives_v0.json').read_text(encoding='utf-8'))
    return (
        '\nExperimental procedural composition is available INSIDE LlmPlan only. '
        'Use component_type=ASSEMBLY with params.fbd_program={dialect:"fbd/0",seed:0,functions:[],main:[]}. '
        'Never combine fbd_program with assembly/ops/graph/macro/preset/skeleton, library requests or another generation authority. '
        'Keep relative_position, dimensions, slot_id and component_id in the normal component envelope. '
        'Set params.generation_authority="ASSEMBLY"; never COMPONENTS. '
        'AST statements: let(id,name,value); if(id,cond,then,else); repeat(id,times,body); '
        'with_frame(id,origin:[dx,dy,dz],body); call(id,fn,args); emit(id,op,params). '
        'Every statement has stmt and globally unique stable id. Functions have name,params:[{name,type}],body. '
        'Types: int(signed32),scalar(finite double),bool,role. References are "$name"; "$i" is reserved for repeat. '
        'Use bare literals, {expr:operator,args:[...]} and {rand:label}; no queries, recursion, imports or host calls. '
        'Operators: +,-,*,/,min,max,abs,floor,lerp,sin,cos,>,>=,<,<=,==,!=,&&,||,!. '
        'int/int truncates toward zero; floor is the only scalar-to-int conversion; sin/cos use degrees. '
        'with_frame translates only. Material roles are wall,window,floor,roof,slab,accent (not block IDs). '
        'HIP and GABLE both use the existing generic pitched-roof algorithm, not distinct roof solvers. '
        'Budget: 64KiB input,2000 JSON values,depth24,5000 cumulative repeats,200000 evaluation steps,20000 expanded ops, '
        '2000000 candidate samples and 200000 candidate writes. Prefer compact programs, not thousands of repeated emits. '
        'SHELL_BOX requires w,d>=5 and h>=6; do not silently enlarge a smaller explicit request: report capability_gap. '
        'These shell/roof/pattern primitives alone do not guarantee entrances or connected interiors; '
        'use ordinary semantic components when their functional guarantees are needed. '
        'Exact supported primitive signatures: ' + json.dumps(signatures, separators=(',', ':')) + '\n'
    )


def fbd_system_prompt(user_text: str) -> str:
    """A dedicated contract avoids legacy preset/MASS instructions overriding FBD."""
    example = json.loads((_ROOT / 'examples/repeated_pavilions.llmplan.json').read_text(encoding='utf-8'))
    return (
        'You are FormaCraft. Return one valid LlmPlan JSON object, without Markdown. '
        'The user explicitly requests FBD. Generate only ASSEMBLY components with params.fbd_program; '
        'do not substitute legacy assembly, presets, MASS components or descriptive repeat metadata. '
        'Preserve explicit counts, dimensions, spacing and seed, including budget-rejection tests. '
        'Use component-relative frames and relative slot anchors; Java supplies the world anchor. '
        'dimensions describes the complete program footprint, including offsets and roof overhang. '
        'If unsupported, return plan_status="capability_gap" with capability_gap containing code,message,path,suggestions. '
        + fbd_prompt_block(user_text)
        + '\nExecutable example (adapt it, do not copy its dimensions over user requirements):\n'
        + json.dumps(example, separators=(',', ':'))
    )


def fbd_request_prompt(req) -> str:
    # Retain placement constraints without carrying legacy schema/history or credentials.
    context = {}
    for name in ('player', 'world', 'selection', 'brushSelection', 'outline', 'protectedZones', 'path'):
        value = getattr(req, name, None)
        if value is not None:
            if isinstance(value, list):
                value = [v.model_dump(mode='json') if hasattr(v, 'model_dump') else v for v in value]
            elif hasattr(value, 'model_dump'):
                value = value.model_dump(mode='json')
            context[name] = value
    return ('Placement context (respect facing, selection and protection):\n'
            + json.dumps(context, ensure_ascii=False)
            + '\nCurrent user request:\n' + (req.userMessage or ''))


def has_fbd_program(plan: dict) -> bool:
    if not isinstance(plan, dict) or not isinstance(plan.get('components'), list):
        return False
    return any(isinstance(c, dict) and isinstance(c.get('params'), dict) and 'fbd_program' in c['params']
               for c in plan['components'])
