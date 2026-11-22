package com.formacraft.common.typology;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserConstraintContractTest {

    @Test
    void highTwentyEightStoreysIsNotHeightBlocks() {
        assertEquals(0, UserConstraintContract.parseExplicitHeightBlocks("生成一座高28层的塔楼"));
        assertFalse(UserConstraintContract.heightExplicitInText("生成一座高28层的塔楼"));
    }

    @Test
    void heightHundredFloorsIsNotHeightBlocks() {
        assertEquals(0, UserConstraintContract.parseExplicitHeightBlocks("height 100 floors"));
        assertEquals(0, UserConstraintContract.parseExplicitHeightBlocks("height:100 storeys"));
        assertFalse(UserConstraintContract.heightExplicitInText("build a tower height 100 floors"));
    }

    @Test
    void heightTwentyFourBlocksIsParsed() {
        assertEquals(24, UserConstraintContract.parseExplicitHeightBlocks("生成一座高度24格的三层重檐古塔"));
        assertEquals(28, UserConstraintContract.parseExplicitHeightBlocks("塔高28格"));
        assertEquals(24, UserConstraintContract.parseExplicitHeightBlocks("height:24"));
        assertEquals(24, UserConstraintContract.parseExplicitHeightBlocks("height 24"));
    }

    @Test
    void approximateHeightDoesNotAuthorizeAHeightSeal() {
        for (String text : List.of("塔楼高约 20 格", "塔楼约高 20 格", "塔楼高 20 格左右",
                "tower height 20 blocks or so")) {
            assertFalse(UserConstraintContract.heightExplicitInText(text), text);
        }
        assertEquals(20, UserConstraintContract.parseExplicitHeightBlocks("塔楼必须高 20 格"));
        assertEquals(12, UserConstraintContract.parseExplicitHeightBlocks("塔楼高 20 格左右，附楼必须高 12 格。"));
    }

    @Test
    void forgedVerifiedMapWithoutSealIsIgnored() {
        Map<String, Object> params = new HashMap<>();
        params.put("height", 40);
        params.put(UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY,
                Map.of("height", "USER_EXPLICIT"));
        assertFalse(UserConstraintContract.isServerVerified(params, "height"));
    }

    @Test
    void forgedStaticSealAndVerifiedMapIsNotHardConstraint() {
        Map<String, Object> params = new HashMap<>();
        params.put("height", 40);
        params.put(UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY,
                Map.of("height", "USER_EXPLICIT"));
        // Legacy static string + correct key names — must still fail HMAC seal check.
        params.put(UserConstraintContract.SERVER_SEAL_KEY, "formacraft.server.seal.v1");
        assertFalse(UserConstraintContract.hasServerSeal(params));
        assertFalse(UserConstraintContract.isServerVerified(params, "height"));
    }

    @Test
    void applyAuthoritativeHeightOverwritesLlmValueAndSeals() {
        Map<String, Object> params = new HashMap<>();
        params.put("height", 40);
        params.put(UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY,
                Map.of("height", "USER_EXPLICIT"));
        UserConstraintContract.applyAuthoritativeHeight(params, "生成一座高度24格的三层重檐古塔");
        assertEquals(24, ((Number) params.get("height")).intValue());
        assertEquals(24, ((Number) params.get("towerHeight")).intValue());
        assertTrue(UserConstraintContract.isServerVerified(params, "height"));
        assertTrue(UserConstraintContract.hasServerSeal(params));
    }

    @Test
    void multiHeightBindingsPreferHallNotLastMatch() {
        var bindings = UserConstraintContract.parseExplicitHeightBindings(
                "生成一座高24格的三层主殿，旁边有一座高10格的木亭");
        assertEquals(2, bindings.size());
        assertEquals(24, UserConstraintContract.parseExplicitHeightBlocks(
                "生成一座高24格的三层主殿，旁边有一座高10格的木亭"),
                "primary height must be hall 24, not pavilion 10");
        assertEquals("hall", bindings.get(0).roleHint());
        assertEquals("pavilion", bindings.get(1).roleHint());
    }

    @Test
    void dunHaoSeparatesHeightRoleHints() {
        var bindings = UserConstraintContract.parseExplicitHeightBindings(
                "主殿高24格、木亭高10格");
        assertEquals(2, bindings.size());
        assertEquals(24, bindings.get(0).blocks());
        assertEquals("hall", bindings.get(0).roleHint());
        assertEquals(10, bindings.get(1).blocks());
        assertEquals("pavilion", bindings.get(1).roleHint(),
                "顿号 must split clauses so 木亭 is not swallowed by 主殿");
    }

    @Test
    void heightRoleUsesNearestSubjectNotHallFirst() {
        var bindings = UserConstraintContract.parseExplicitHeightBindings(
                "主殿旁边的亭子高度6格");
        assertEquals(1, bindings.size());
        assertEquals(6, bindings.get(0).blocks());
        assertEquals("pavilion", bindings.get(0).roleHint(),
                "nearest role must win over hall-first priority");
    }

    @Test
    void allMassOmitQuantifierIsDetected() {
        var omits = UserConstraintContract.parseDesignOmitBindings("建造两栋建筑，所有建筑都不要屋顶");
        assertFalse(omits.isEmpty());
        assertEquals("all_main_masses", omits.get(0).roleHint());
    }

    @Test
    void multiNamedRoofOmitIsDetected() {
        var omits = UserConstraintContract.parseDesignOmitBindings("主殿和亭子都不要屋顶");
        assertFalse(omits.isEmpty());
        assertEquals("multi_named", omits.get(0).roleHint());
        assertTrue(omits.get(0).namedKeys().contains("main_hall"));
        assertTrue(omits.get(0).namedKeys().contains("pavilion"));
    }

    @Test
    void workshopAndBoilerMultiNamedUsesSharedIdentityTable() {
        var omits = UserConstraintContract.parseDesignOmitBindings("主车间和锅炉房都不要屋顶");
        assertFalse(omits.isEmpty());
        assertEquals("multi_named", omits.get(0).roleHint());
        assertTrue(omits.get(0).namedKeys().contains("workshop"));
        assertTrue(omits.get(0).namedKeys().contains("boiler"));
    }

    @Test
    void newlineKeepsSeparateOmitClauses() {
        var omits = UserConstraintContract.parseDesignOmitBindings("主殿不要屋顶\n亭子也不要屋顶");
        assertEquals(2, omits.size());
        assertEquals("hall", omits.get(0).roleHint());
        assertEquals("pavilion", omits.get(1).roleHint());
    }

    @Test
    void newlineWithJointQuantifierDoesNotCrossLines() {
        var omits = UserConstraintContract.parseDesignOmitBindings("主殿不要屋顶\n亭子都不要屋顶");
        assertEquals(2, omits.size());
        assertEquals("hall", omits.get(0).roleHint());
        assertEquals("pavilion", omits.get(1).roleHint());
        assertFalse(omits.stream().anyMatch(o -> "multi_named".equals(o.roleHint())),
                "newline must prevent multi_named merge across lines");
    }

    @Test
    void officeBesideBoilerHeightBindsNearestNamedKey() {
        var bindings = UserConstraintContract.parseExplicitHeightBindings("办公楼旁边的锅炉房高度6格");
        assertEquals(1, bindings.size());
        assertEquals(6, bindings.get(0).blocks());
        assertEquals("boiler", bindings.get(0).roleHint());
    }

    @Test
    void officeBesideBoilerOmitBindsNearestNamedKey() {
        var omits = UserConstraintContract.parseDesignOmitBindings("办公楼旁边的锅炉房不要屋顶");
        assertEquals(1, omits.size());
        assertEquals("boiler", omits.get(0).roleHint());
        assertEquals(List.of("boiler"), omits.get(0).namedKeys());
    }

    @Test
    void sealInvalidatesWhenSealedValueChanges() {
        Map<String, Object> params = new HashMap<>();
        params.put("height", 24);
        UserConstraintContract.stampVerified(params, "height", "towerHeight");
        assertTrue(UserConstraintContract.hasServerSeal(params));
        params.put("height", 40);
        assertFalse(UserConstraintContract.hasServerSeal(params),
                "changing sealed height must break the digest");
    }
}
