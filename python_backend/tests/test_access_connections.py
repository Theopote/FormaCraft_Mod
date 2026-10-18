import json
import unittest
from pathlib import Path
from app.services.circulation_plan_normalizer import normalize_circulation_plan


def component(kind, identity, x, y, z, w, d, h, **params):
    return dict(component_type=kind, slot_id="s", relative_position=dict(x=x,y=y,z=z),
                dimensions=dict(width=w,depth=d,height=h), features=[],
                params=dict(component_id=identity,anchor_mode="min_corner",**params))


class AccessConnectionsTest(unittest.TestCase):
    def test_logged_plans_and_idempotence(self):
        root=Path(__file__).parent/"fixtures/complex_build_retest"
        for i in range(1,4):
            raw=json.loads((root/f"oct18_access_{i}.json").read_text(encoding="utf-8"))
            result=normalize_circulation_plan(raw)
            self.assertEqual(result,normalize_circulation_plan(result))
            self.assertEqual(result,json.loads((root/f"oct18_access_{i}.normalized.json").read_text(encoding="utf-8")))
            if i==2:
                stair=next(c for c in result["components"] if c["params"].get("component_id")=="townhall_stair")
                self.assertEqual("STRUCTURE",stair["component_type"])
                self.assertEqual(6,stair["params"]["to"]["y"])
            if i==3:
                deck=next(c for c in result["components"] if c["params"].get("component_id")=="workshop_platform_plate")
                self.assertEqual("partial_platform",deck["params"]["plate_role"])
                self.assertEqual(dict(width=5,depth=6,height=1),deck["dimensions"])

    def test_platform_connects_stair_to_wall(self):
        from app.services.circulation_plan_normalizer import _complete_exterior_target_doors
        body=component("MASS_MAIN","body",0,0,0,13,11,10,floor_height=5,floor_count=2)
        deck=component("MASS_SECONDARY","deck",12,5,2,6,7,1,host_id="body",extrude_mode="plate",plate_role="partial_platform")
        stair=component("STRUCTURE","stair",20,0,5,3,6,8,exterior=True,target_host_id="body",width=3,
                        **{"from":dict(x=0,y=0,z=0),"to":dict(x=-5,y=5,z=0)})
        plan=dict(components=[body,deck,stair])
        _complete_exterior_target_doors(plan)
        doors=[c for c in plan["components"] if c["component_type"]=="ENTRANCE"]
        self.assertEqual(1,len(doors))
        self.assertEqual(dict(x=12,y=5,z=4),doors[0]["relative_position"])
        self.assertEqual("WEST",doors[0]["params"]["facing"])
        _complete_exterior_target_doors(plan)
        self.assertEqual(4,len(plan["components"]))
        for missing in (True,False):
            invalid=dict(components=[body,stair] if missing else [body,dict(deck,relative_position=dict(x=14,y=5,z=2)),stair])
            _complete_exterior_target_doors(invalid)
            self.assertFalse(any(c["component_type"]=="ENTRANCE" for c in invalid["components"]))
        disabled=dict(components=[body,deck,stair],plan_meta=dict(allow_compiler_satellites=False))
        _complete_exterior_target_doors(disabled)
        self.assertEqual(3,len(disabled["components"]))
