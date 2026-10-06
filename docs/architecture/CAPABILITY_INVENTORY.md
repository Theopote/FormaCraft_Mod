# 建筑风格与结构类型能力盘点

由 `python tools/audit_architecture_capabilities.py` 从当前目录生成。配置和注册证据不等于游戏效果验收。

## 风格目录

| 风格 ID | 材料目录 | 文化卡片数 | 证据 |
|---|---|---:|---|
| Chinese_Imperial_Official | PALETTE_CHINESE_IMPERIAL_A | 2 | 已配置规则，未判定游戏验收 |
| Chinese_Vernacular_Tulou | PALETTE_EAST_ASIAN_WOOD_A | 0 | 已配置规则，未判定游戏验收 |
| Japanese_Traditional | PALETTE_EAST_ASIAN_WOOD_A | 1 | 已配置规则，未判定游戏验收 |
| Gothic_Cathedral | PALETTE_GOTHIC_CATHEDRAL_A | 2 | 已配置规则，未判定游戏验收 |
| Medieval_Castle | PALETTE_STONE_FORTRESS_A | 3 | 已配置规则，未判定游戏验收 |
| Modern_International | PALETTE_MODERN_GLASS_B | 3 | 已配置规则，未判定游戏验收 |
| Brutalism | PALETTE_BRUTALISM_CONCRETE_A | 0 | 已配置规则，未判定游戏验收 |
| Deconstructivism_Zaha | PALETTE_PARAMETRIC_WHITE_A | 0 | 已配置规则，未判定游戏验收 |
| Industrial_Structure | PALETTE_INDUSTRIAL_STEEL_A | 2 | 已配置规则，未判定游戏验收 |
| Fantasy_Elven | PALETTE_ELVEN_ORGANIC_A | 0 | 已配置规则，未判定游戏验收 |
| Greco_Roman_Classical | PALETTE_CLASSICAL_MARBLE_A | 1 | 已配置规则，未判定游戏验收 |
| Chinese_Vernacular_Huizhou | PALETTE_HUIZHOU_WHITE_BLACK_A | 1 | 已配置规则，未判定游戏验收 |
| Chinese_Vernacular_Jiangnan_WaterTown | PALETTE_JIANGNAN_WATERTOWN_A | 0 | 已配置规则，未判定游戏验收 |
| Steampunk | PALETTE_STEAMPUNK_COPPER_A | 0 | 已配置规则，未判定游戏验收 |
| Cyberpunk | PALETTE_CYBER_NEON_A | 0 | 已配置规则，未判定游戏验收 |

## 结构类型

| 类型 | 生成路径 | 证据 |
|---|---|---|
| dense_eaves_pagoda | typology_first | registered_interpreter |
| tailiang_timber_hall | typology_first | registered_interpreter |
| radial_terrace_hall | typology_first | registered_interpreter |
| stadium_bowl | typology_first | registered_interpreter |
| suspension_bridge | typology_first | registered_interpreter |
| gothic_cathedral_hall | typology_first | registered_interpreter |
| courtyard_compound | typology_first | registered_interpreter |
| radial_fortress | typology_first | registered_interpreter |
| setback_tower | typology_first | registered_interpreter |
| tiered_mountain_palace | typology_first | registered_interpreter |

## 等价别名

- Classical_GrecoRoman → Greco_Roman_Classical
- Industrial_Structural → Industrial_Structure

## 已识别但尚无独立风格规则的变体

- Chinese_Traditional：family；保留身份，不冒充已完成生成能力。
- Tang_Dynasty_Timber：historical_variant；保留身份，不冒充已完成生成能力。
- Cottage_Rural：vernacular_variant；保留身份，不冒充已完成生成能力。
- Modern_Stadium_Elliptical：purpose_variant；保留身份，不冒充已完成生成能力。
- Modern_Stadium_Creative：purpose_variant；保留身份，不冒充已完成生成能力。

## 非风格标识

- Patch_Edit

## 待统一的文化风格标识

目录外标识均已明确分类；变体几何规则仍待完善。

## 配置错误

未发现缺失材料目录或未注册的专用解释器。
