package com.formacraft.common.mass.integration;

import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.mass.*;
import com.formacraft.common.mass.integration.BuildingMassPipeline.BuildingMassPipelineResult;
import com.formacraft.FormacraftMod;

import java.util.List;

/**
 * BuildingMassSystemTest（建筑体量系统测试）
 * <p>
 * 提供简单的测试方法，验证 BuildingMass 系统是否正常工作
 * <p>
 * 可以通过命令或调试工具调用
 */
public final class BuildingMassSystemTest {

    private BuildingMassSystemTest() {}

    /**
     * 运行基础测试
     * <p>
     * 创建一个简单的体量组合，验证流程是否正常运行
     */
    public static void runBasicTest() {
        FormacraftMod.LOGGER.info("=== BuildingMass System Test ===");

        try {
            // 1. 创建简单的 PlanSkeleton（Domain）
            PlanSkeleton domain = createTestDomain();

            // 2. 创建体量组合
            BuildingMass mainMass = BuildingMassBuilder.createRectangularMass(
                    "test_main",
                    0, 20, 0, 10,
                    64, 76, // 3 层（每层 4 block）
                    MassType.SOLID,
                    MassRole.PRIMARY
            );

            BuildingMassComposition composition = BuildingMassComposition.empty(domain)
                    .withMass(mainMass);

            // 3. 验证体量占用检查
            testMassFilledChecker(composition);

            // 4. 执行完整流程
            BuildingMassPipelineResult result = BuildingMassPipeline.execute(domain, 64);

            // 5. 输出结果
            FormacraftMod.LOGGER.info("Test Result:");
            FormacraftMod.LOGGER.info("  - Skeletons: {}", result.skeletons.size());
            FormacraftMod.LOGGER.info("  - Layers: {}", result.layers.size());
            FormacraftMod.LOGGER.info("  - Layered Sockets: {}", result.layeredSockets.size());
            FormacraftMod.LOGGER.info("  - Processed Sockets: {}", result.getAllProcessedSockets().size());

            FormacraftMod.LOGGER.info("=== BuildingMass System Test PASSED ===");

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("=== BuildingMass System Test FAILED ===", e);
        }
    }

    /**
     * 测试 MassFilledChecker
     */
    private static void testMassFilledChecker(BuildingMassComposition composition) {
        // 测试几个位置的占用情况
        boolean filled1 = MassFilledChecker.isFilled(composition, 10, 65, 5); // 应该被占用
        boolean filled2 = MassFilledChecker.isFilled(composition, 30, 65, 5); // 应该不被占用

        FormacraftMod.LOGGER.info("MassFilledChecker Test:");
        FormacraftMod.LOGGER.info("  - (10, 65, 5) filled: {}", filled1);
        FormacraftMod.LOGGER.info("  - (30, 65, 5) filled: {}", filled2);

        if (!filled1 || filled2) {
            throw new AssertionError("MassFilledChecker test failed");
        }
    }

    /**
     * 创建测试用的 PlanSkeleton
     */
    private static PlanSkeleton createTestDomain() {
        // v1 简化：创建一个最小的 PlanSkeleton
        // 未来：可以使用 PlanSkeletonParser 解析 JSON
        return new PlanSkeleton(
                null, // schema
                null, // outline（v1 简化：null）
                List.of(), // zones
                List.of(), // edges
                List.of(), // courtyards
                List.of()  // axes
        );
    }
}
