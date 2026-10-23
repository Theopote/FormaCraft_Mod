"""Opt-in FBD prompting from the same primitive signatures used by Java."""
import json
import os
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/formacraft/fbd'


def fbd_prompt_block() -> str:
    if (os.getenv('LLMPLAN_FBD') or 'off').strip().lower() not in ('1', 'true', 'on'):
        return ''
    signatures = json.loads((_ROOT / 'primitives_v0.json').read_text(encoding='utf-8'))
    return (
        '\nExperimental procedural composition is available INSIDE LlmPlan only. '
        'Use component_type=ASSEMBLY with params.fbd_program={dialect:"fbd/0",seed:0,functions:[],main:[]}. '
        'Never combine fbd_program with assembly/ops/graph/macro/preset/skeleton, library requests or another generation authority. '
        'Keep relative_position, dimensions, slot_id and component_id in the normal component envelope. '
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
        '200000 conservative candidate samples/writes. Prefer compact programs, not thousands of repeated emits. '
        'These shell/roof/pattern primitives alone do not guarantee entrances or connected interiors; '
        'use ordinary semantic components when their functional guarantees are needed. '
        'Exact supported primitive signatures: ' + json.dumps(signatures, separators=(',', ':')) + '\n'
    )


def has_fbd_program(plan: dict) -> bool:
    if not isinstance(plan, dict) or not isinstance(plan.get('components'), list):
        return False
    return any(isinstance(c, dict) and isinstance(c.get('params'), dict) and 'fbd_program' in c['params']
               for c in plan['components'])
