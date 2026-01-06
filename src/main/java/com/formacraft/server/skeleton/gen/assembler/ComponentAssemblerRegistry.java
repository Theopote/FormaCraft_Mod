package com.formacraft.server.skeleton.gen.assembler;

import com.formacraft.common.component.ComponentType;
import com.formacraft.server.skeleton.gen.assembler.impl.CourtyardAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.EnclosingWallAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.GateAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.RoofRingAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.StairAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.TowerAssembler;
import com.formacraft.server.skeleton.gen.assembler.impl.WalkwayAssembler;

import java.util.EnumMap;
import java.util.Map;

/**
 * 组件装配器注册表
 */
public final class ComponentAssemblerRegistry {

    private static final Map<ComponentType, ComponentAssembler> MAP =
            new EnumMap<>(ComponentType.class);

    private ComponentAssemblerRegistry() {}

    /**
     * 注册默认装配器
     */
    public static void registerDefaults() {
        // 围合结构
        MAP.put(ComponentType.ENCLOSING_WALL, new EnclosingWallAssembler());
        
        // 垂直体
        MAP.put(ComponentType.TOWER, new TowerAssembler());
        
        // 场地/空间
        MAP.put(ComponentType.COURTYARD, new CourtyardAssembler());
        
        // 屋顶
        MAP.put(ComponentType.ROOF_RING, new RoofRingAssembler());
        
        // 交通器官
        MAP.put(ComponentType.GATE, new GateAssembler());
        MAP.put(ComponentType.STAIR, new StairAssembler());
        MAP.put(ComponentType.WALKWAY, new WalkwayAssembler());
        
        // TODO: 后续添加更多装配器
        // MAP.put(ComponentType.FOUNDATION, new FoundationAssembler());
        // MAP.put(ComponentType.DOOR, new DoorAssembler());
        // MAP.put(ComponentType.WINDOW_BAND, new WindowBandAssembler());
    }

    /**
     * 注册装配器
     */
    public static void register(ComponentType type, ComponentAssembler assembler) {
        if (type != null && assembler != null) {
            MAP.put(type, assembler);
        }
    }

    /**
     * 获取装配器
     */
    public static ComponentAssembler get(ComponentType type) {
        return MAP.get(type);
    }

    /**
     * 检查是否有装配器
     */
    public static boolean has(ComponentType type) {
        return MAP.containsKey(type);
    }
}

