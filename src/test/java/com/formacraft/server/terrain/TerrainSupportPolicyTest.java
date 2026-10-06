package com.formacraft.server.terrain;

import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainSupportPolicyTest {
    @BeforeAll static void bootstrap(){com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();}
    private static TerrainSupportPolicy policy(String text){var request=new FormaRequest();request.setUserMessage(text);return TerrainSupportPolicy.fromRequest(request);}
    @Test void automaticSupportDoesNotInventAnAccessRoad(){assertFalse(policy("建造一栋住宅，内部设置楼梯").access());}
    @Test void explicitSupportMaterialAndTypeAreIndependent(){
        var result=policy("采用垂直支撑柱，支柱使用石砖，入口通过台阶连接自然地面");
        assertEquals(TerrainSupportPolicy.Mode.VERTICAL,result.mode());assertTrue(result.material().isOf(Blocks.STONE_BRICKS));assertTrue(result.access());
    }
    @Test void explicitNoRoadOverridesPositiveConnection(){assertFalse(policy("入口连接自然地面，但不要生成道路，通路后补").access());}
    @Test void explicitBracesAndSolidFoundationAreRecognized(){
        assertEquals(TerrainSupportPolicy.Mode.DIAGONAL,policy("采用斜撑").mode());
        assertEquals(TerrainSupportPolicy.Mode.SOLID,policy("采用实心地基").mode());
        assertEquals(TerrainSupportPolicy.Mode.VERTICAL,policy("不要使用斜撑").mode());
    }
    @Test void unknownExplicitSupportMaterialIsNotReplaced(){assertThrows(IllegalArgumentException.class,()->policy("支柱使用minecraft:missing_support"));}
}
