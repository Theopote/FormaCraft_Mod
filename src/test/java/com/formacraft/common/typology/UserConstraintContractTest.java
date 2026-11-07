package com.formacraft.common.typology;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserConstraintContractTest {

    @Test
    void highTwentyEightStoreysIsNotHeightBlocks() {
        assertEquals(0, UserConstraintContract.parseExplicitHeightBlocks("生成一座高28层的塔楼"));
        assertFalse(UserConstraintContract.heightExplicitInText("生成一座高28层的塔楼"));
    }

    @Test
    void heightTwentyFourBlocksIsParsed() {
        assertEquals(24, UserConstraintContract.parseExplicitHeightBlocks("生成一座高度24格的三层重檐古塔"));
        assertEquals(28, UserConstraintContract.parseExplicitHeightBlocks("塔高28格"));
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
}
