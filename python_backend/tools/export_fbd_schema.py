"""Generate the experimental fbd/0 structural schema from shared primitive signatures."""
import json
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/formacraft/fbd'


def build_schema():
    ref = lambda name: {'$ref': '#/$defs/' + name}
    identifier = {'type': 'string', 'maxLength': 64, 'pattern': r'^(?!i$)[a-z][a-zA-Z0-9_]*$'}
    variable = {'type': 'string', 'maxLength': 65, 'pattern': r'^\$[a-zA-Z][a-zA-Z0-9_]*$'}
    roles = ['wall', 'window', 'floor', 'roof', 'slab', 'accent']

    def obj(properties, required=None):
        return {'type': 'object', 'additionalProperties': False,
                'required': list(properties) if required is None else required, 'properties': properties}

    fields = {
        'let': ({'name': identifier, 'value': ref('expr'), 'doc': {'type': 'string'}}, ['name', 'value']),
        'if': ({'cond': ref('expr'), 'then': ref('body'), 'else': ref('body')}, ['cond', 'then']),
        'repeat': ({'times': ref('expr'), 'body': ref('body')}, ['times', 'body']),
        'with_frame': ({'origin': {'type': 'array', 'minItems': 3, 'maxItems': 3, 'items': ref('expr')}, 'body': ref('body')}, ['origin', 'body']),
        'call': ({'fn': identifier, 'args': {'type': 'object', 'propertyNames': identifier, 'additionalProperties': ref('expr')}}, ['fn', 'args']),
    }
    statements = [obj({'stmt': {'const': kind}, 'id': ref('nodeId'), **props}, ['stmt', 'id', *required])
                  for kind, (props, required) in fields.items()]
    signatures = json.loads((ASSETS / 'primitives_v0.json').read_text(encoding='utf-8'))
    for op, signature in signatures.items():
        params = {}
        for name, field in signature.items():
            if 'enum' in field:
                literal = {'enum': field['enum']}
            elif field['type'] == 'role':
                literal = {'enum': roles}
            else:
                literal = {'type': 'integer' if field['type'] == 'int' else 'number',
                           **({'minimum': field['min'], 'maximum': field['max']} if 'min' in field else {})}
            params[name] = {'anyOf': [literal, variable, ref('exprObject')]}
        statements.append(obj({'stmt': {'const': 'emit'}, 'id': ref('nodeId'), 'op': {'const': op},
                               'params': obj(params, [key for key, value in signature.items() if value.get('required')])}))
    operator_schemas = []
    for names, minimum, maximum in [(['+', '-', '*', '/', '>', '>=', '<', '<=', '==', '!=', '&&', '||'], 2, 2),
                                     (['abs', 'floor', 'sin', 'cos', '!'], 1, 1), (['lerp'], 3, 3), (['min', 'max'], 2, 8)]:
        operator_schemas.append(obj({'expr': {'enum': names}, 'args': {'type': 'array', 'minItems': minimum,
                                                                 'maxItems': maximum, 'items': ref('expr')}}))
    return {'$schema': 'https://json-schema.org/draft/2020-12/schema', 'title': 'Experimental fbd/0 program',
            **obj({'dialect': {'const': 'fbd/0'}, 'seed': {'type': 'integer', 'minimum': 0, 'maximum': 9223372036854775807},
                   'functions': {'type': 'array', 'items': ref('function')}, 'main': ref('body')}, ['dialect', 'seed', 'main']),
            '$defs': {'nodeId': {'type': 'string', 'maxLength': 64, 'pattern': r'^[a-zA-Z][a-zA-Z0-9_]*$'},
                      'body': {'type': 'array', 'items': {'oneOf': statements}},
                      'expr': {'anyOf': [{'type': 'number'}, {'type': 'boolean'}, variable, {'enum': roles},
                                        {'type': 'string', 'pattern': r'^[A-Z][A-Z0-9_]*$'}, ref('exprObject')]},
                      'exprObject': {'oneOf': operator_schemas + [obj({'rand': identifier})]},
                      'function': obj({'name': identifier, 'params': {'type': 'array', 'items': obj({'name': identifier, 'type': {'enum': ['int', 'scalar', 'bool', 'role']}})},
                                       'body': ref('body'), 'doc': {'type': 'string'}}, ['name', 'params', 'body'])}}


if __name__ == '__main__':
    (ASSETS / 'program.schema.json').write_text(json.dumps(build_schema(), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
