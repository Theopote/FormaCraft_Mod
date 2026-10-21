import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from app.services.user_style_packs import discover

ROOT=Path(__file__).resolve().parents[2]
EXAMPLE=ROOT/'docs/examples/style-packs/lake'


class UserStylePacksTest(unittest.TestCase):
    def test_example_is_a_complete_additive_pack(self):
        assets,reports=discover(EXAMPLE.parent)
        self.assertEqual([True],[r['accepted'] for r in reports],reports)
        self.assertEqual(11,len(assets))
        profiles=assets['style_profiles/style_profile_catalog_v1.json']['profiles']
        self.assertIn('lake_hall',profiles)
        self.assertIn('Alpine_Chalet',profiles)

    def reject(self, edit):
        with tempfile.TemporaryDirectory() as directory:
            pack=Path(directory)/'lake'; shutil.copytree(EXAMPLE,pack)
            edit(pack)
            assets,reports=discover(directory)
            self.assertEqual({},assets)
            self.assertEqual(1,len(reports))
            self.assertFalse(reports[0]['accepted'],reports)

    def test_invalid_packs_commit_nothing(self):
        mutations=[
            ('style-pack.json',lambda d:d.update(schema='unsupported')),
            ('style-pack.json',lambda d:d['files'].append('../outside.json')),
            ('style-pack.json',lambda d:d['files'].append(d['files'][0])),
            ('style_profiles/style_profile_catalog_v1.json',lambda d:d['profiles'].update(Modern_International=d['profiles']['lake_hall'])),
            ('culture_cards/lake_hall.json',lambda d:d.update(styleId='missing')),
            ('culture_cards/lake_hall.json',lambda d:d.update(llmPlanExampleRefs=['../../outside.json'])),
            ('culture_cards/lake_hall.json',lambda d:d.update(exampleRefs=['../../outside.json'])),
            ('style_genomes/lake_hall.json',lambda d:d['palette'].pop('door')),
            ('structural_typologies/structural_typologies_v1.json',lambda d:d['typologies'][0].update(interpreterId='custom_code')),
            ('style_profiles/style_identity_catalog_v1.json',lambda d:d['lexical_aliases']['lake_hall'].append('Industrial')),
            ('style_profiles/style_identity_catalog_v1.json',lambda d:d['lexical_aliases'].update(lake_missing=['missing user profile'])),
            ('style_visual_cases/catalog_v1.json',lambda d:d['cases'][0]['feature_groups'].update(roof='not a term array')),
        ]
        for relative,mutation in mutations:
            with self.subTest(asset=relative):
                def edit(pack):
                    path=pack/relative; data=json.loads(path.read_text(encoding='utf-8')); mutation(data)
                    path.write_text(json.dumps(data),encoding='utf-8')
                self.reject(edit)

    def test_corrupt_and_oversized_files_are_rejected(self):
        self.reject(lambda pack:(pack/'culture_cards/lake_hall.json').write_text('{broken',encoding='utf-8'))
        self.reject(lambda pack:(pack/'culture_cards/lake_hall.json').write_text(' '*262145,encoding='utf-8'))

    def test_invalid_pack_isolated_from_valid_sibling_and_cli_reports_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            shutil.copytree(EXAMPLE,Path(directory)/'lake')
            shutil.copytree(EXAMPLE,Path(directory)/'bad')
            (Path(directory)/'bad/style-pack.json').write_text('{}',encoding='utf-8')
            assets,reports=discover(directory)
            self.assertIn('culture_cards/lake_hall.json',assets)
            self.assertEqual([False,True],[report['accepted'] for report in reports])
            process=subprocess.run([sys.executable,'-m','eval.validate_style_packs',directory],
                cwd=ROOT/'python_backend',capture_output=True,text=True)
            self.assertEqual(1,process.returncode,process.stdout+process.stderr)

    def test_real_consumers_load_user_assets_in_a_fresh_backend(self):
        script='''
from app.services.style_identity import canonical_style_id
from app.services.style_profile_registry import get_style_profile
from app.services.keyword_culture_retriever import retrieve
from app.services.building_request_classifier import classify_building_request_local
from app.services.ai_planner import _fill_component_params
from app.services.ornament_contract import apply_ornament_contracts
from app.services.user_style_packs import ASSETS, read_asset
assert canonical_style_id('lakeside timber')=='lake_hall'
assert get_style_profile('lakeside timber').style_id=='lake_hall'
r=retrieve('build a lakeside timber',topK=1)
assert r['hits'][0]['id']=='lake_hall',r
assert r['llmPlanFewShots'],r
assert r['proportionCard']['id']=='lake_hall',r
assert r['structuralTypologyId']=='lake_house',r
assert not classify_building_request_local('build a lakeside timber').is_specific_real_building
assert classify_building_request_local('build a lakeside timber Osaka house').is_specific_real_building
p={}
_fill_component_params(p,{'component_type':'MASS_MAIN','_style_hint':'lake_hall','features':[]},{})
assert p['wall_block']=='minecraft:spruce_planks',p
plan=read_asset(ASSETS/'llmplan_examples/lake_small.json')['plan']
apply_ornament_contracts(plan,'no complex decoration')
assert plan['components'][0]['params']['no_complex_decor'] is True
assert plan['proportion_hints']['lake_decor_report']['requirements'][0]['status']=='planned'
'''
        process=subprocess.run([sys.executable,'-c',script],cwd=ROOT/'python_backend',
            env={**os.environ,'FORMACRAFT_STYLE_PACKS_DIR':str(EXAMPLE.parent),'PYTHONIOENCODING':'utf-8'},capture_output=True,text=True,encoding='utf-8')
        self.assertEqual(0,process.returncode,process.stdout+process.stderr)
