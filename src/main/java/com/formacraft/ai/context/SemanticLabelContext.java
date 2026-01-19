package com.formacraft.ai.context;

import com.formacraft.client.tool.SemanticLabelTool;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 区域语义标注语义层：把 SemanticLabelTool 的标签拼接进 Prompt。
 */
public final class SemanticLabelContext {
    private SemanticLabelContext() {}

    public static boolean hasLabels() {
        return SemanticLabelTool.INSTANCE.hasLabels();
    }

    public static List<SemanticLabelTool.AreaLabel> labels() {
        return SemanticLabelTool.INSTANCE.getLabels();
    }

    public static String toPromptBlock() {
        if (!hasLabels()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("区域语义标注（CRITICAL - MUST USE FOR GUIDED GENERATION）：\n");
        sb.append("- 以下标签把自然语言意图与空间区域绑定，你必须严格遵守并利用这些标签指导建筑生成\n");
        sb.append("- 每个标签都有明确的建筑语义含义，你需要根据标签名称来理解应该在该区域生成什么类型的建筑元素\n");
        sb.append("- range 字段表示标签的作用范围（方块半径），影响周边区域的建筑设计决策\n");
        sb.append("- 多个标签可以组合使用，形成复杂的建筑布局\n\n");
        
        int i = 1;
        for (SemanticLabelTool.AreaLabel l : labels()) {
            if (l == null || l.outline() == null || l.outline().size() < 3) continue;
            
            String labelName = l.name() != null ? l.name().trim() : "未命名";
            String semanticMeaning = inferSemanticMeaning(labelName);
            String designGuidance = inferDesignGuidance(labelName);
            
            sb.append("  Area ").append(i++).append(" - 标签: \"").append(labelName).append("\"\n");
            sb.append("  - 语义含义: ").append(semanticMeaning).append("\n");
            sb.append("  - 设计指导: ").append(designGuidance).append("\n");
            sb.append("  - 作用范围: ").append(l.range()).append(" 格（该范围内的建筑应该体现此标签的特征）\n");
            sb.append("  - 高度范围: Y=").append(l.minY()).append("~").append(l.maxY()).append("\n");
            sb.append("  - 区域边界（多边形顶点，X,Z 坐标）：\n");
            for (BlockPos p : l.outline()) {
                sb.append("    - (").append(p.getX()).append(",").append(p.getZ()).append(")\n");
            }
            sb.append("\n");
        }
        
        sb.append("使用规则：\n");
        sb.append("- 当生成建筑的组件时，检查该组件的位置是否落在某个标签区域内\n");
        sb.append("- 如果落在标签区域内，必须根据标签的语义含义来设计该组件\n");
        sb.append("- 例如：标签为\"入口\"的区域应该包含门、台阶、装饰性元素；标签为\"庭院\"的区域应该是开放的、没有屋顶的空间\n");
        sb.append("- 标签的 range 值告诉你该标签影响的周边范围，在该范围内应该保持一致的建筑特征\n");
        
        return sb.toString();
    }
    
    /**
     * 从标签名称推断语义含义
     */
    private static String inferSemanticMeaning(String labelName) {
        if (labelName == null || labelName.isEmpty()) {
            return "未定义";
        }
        
        String lower = labelName.toLowerCase();
        
        // 中文标签
        if (lower.contains("入口") || lower.contains("entrance")) {
            return "建筑主入口位置，需要门、台阶、装饰性元素，通常是建筑的焦点";
        }
        if (lower.contains("庭院") || lower.contains("courtyard") || lower.contains("中庭")) {
            return "开放庭院空间，不覆盖屋顶，较低高度，通常作为建筑内部的开放区域";
        }
        if (lower.contains("住宅") || lower.contains("residential") || lower.contains("居室")) {
            return "居住空间，需要窗户、门、合理的空间布局，体现居住功能";
        }
        if (lower.contains("商业") || lower.contains("commercial") || lower.contains("商店") || lower.contains("商铺")) {
            return "商业空间，需要展示窗口、入口、招牌位置，面向街道";
        }
        if (lower.contains("仓库") || lower.contains("warehouse") || lower.contains("库房")) {
            return "存储空间，较少窗户，注重实用性，通常较大空间";
        }
        if (lower.contains("办公") || lower.contains("office") || lower.contains("工作")) {
            return "办公空间，需要窗户、合理的空间分割，功能性优先";
        }
        if (lower.contains("厨房") || lower.contains("kitchen")) {
            return "厨房区域，需要功能性布局，考虑通风和实用";
        }
        if (lower.contains("卧室") || lower.contains("bedroom") || lower.contains("房间")) {
            return "卧室或房间，需要窗户、门、私密性考虑";
        }
        if (lower.contains("大厅") || lower.contains("hall") || lower.contains("厅堂")) {
            return "大厅空间，通常是开放、高大的空间，作为主要活动区域";
        }
        if (lower.contains("走廊") || lower.contains("corridor") || lower.contains("过道")) {
            return "连接通道，需要连接不同区域，考虑通行便利";
        }
        if (lower.contains("楼梯") || lower.contains("stair") || lower.contains("阶梯")) {
            return "楼梯位置，需要连接不同楼层，考虑垂直交通";
        }
        if (lower.contains("阳台") || lower.contains("balcony") || lower.contains("露台")) {
            return "阳台空间，向外突出，需要栏杆、视野考虑";
        }
        if (lower.contains("花园") || lower.contains("garden") || lower.contains("园林")) {
            return "花园区域，开放空间，可以有装饰性建筑元素";
        }
        if (lower.contains("广场") || lower.contains("plaza") || lower.contains("空地")) {
            return "开放广场，不建造建筑，或只有装饰性建筑元素";
        }
        if (lower.contains("停车场") || lower.contains("parking")) {
            return "停车区域，通常平坦，可能有简单的结构";
        }
        if (lower.contains("防御") || lower.contains("defensive") || lower.contains("守卫")) {
            return "防御性区域，需要围墙、瞭望塔等防御元素";
        }
        if (lower.contains("神圣") || lower.contains("sacred") || lower.contains("宗教") || lower.contains("temple") || lower.contains("庙")) {
            return "宗教或神圣空间，需要装饰性元素、对称布局、庄重感";
        }
        if (lower.contains("公共") || lower.contains("public")) {
            return "公共空间，需要开放、宽敞、便于集会";
        }
        if (lower.contains("私人") || lower.contains("private")) {
            return "私密空间，需要围合、较少窗户或遮挡";
        }
        
        // 默认：根据标签名称的字面意思推测
        return "根据标签名称 \"" + labelName + "\" 理解其建筑功能含义，并在该区域生成相应的建筑元素";
    }
    
    /**
     * 从标签名称推断设计指导
     */
    private static String inferDesignGuidance(String labelName) {
        if (labelName == null || labelName.isEmpty()) {
            return "遵循一般建筑设计原则";
        }
        
        String lower = labelName.toLowerCase();
        
        // 中文标签
        if (lower.contains("入口") || lower.contains("entrance")) {
            return "在此区域必须包含门（可能是主门），添加台阶、装饰性元素（如门楣、门柱），使其成为建筑的视觉焦点";
        }
        if (lower.contains("庭院") || lower.contains("courtyard") || lower.contains("中庭")) {
            return "此区域应该保持开放，不覆盖屋顶，高度较低，可以包含装饰性围墙、花坛、路径等元素";
        }
        if (lower.contains("住宅") || lower.contains("residential") || lower.contains("居室")) {
            return "在此区域生成居住建筑，包含窗户、门、合理的房间布局，使用居住建筑常见的材料和装饰";
        }
        if (lower.contains("商业") || lower.contains("commercial") || lower.contains("商店") || lower.contains("商铺")) {
            return "在此区域生成商业建筑，包含展示窗口、宽敞入口、招牌位置，面向街道或主要通道";
        }
        if (lower.contains("仓库") || lower.contains("warehouse") || lower.contains("库房")) {
            return "在此区域生成仓储建筑，较少装饰，注重实用性，可能有大型门用于货物进出";
        }
        if (lower.contains("办公") || lower.contains("office") || lower.contains("工作")) {
            return "在此区域生成办公建筑，包含合理分布的窗户、功能性布局，体现办公环境的特征";
        }
        if (lower.contains("厨房") || lower.contains("kitchen")) {
            return "在此区域设计厨房空间，考虑功能性布局，可能需要通风元素";
        }
        if (lower.contains("卧室") || lower.contains("bedroom") || lower.contains("房间")) {
            return "在此区域设计卧室，包含窗户、门，考虑私密性和舒适性";
        }
        if (lower.contains("大厅") || lower.contains("hall") || lower.contains("厅堂")) {
            return "在此区域生成高大的大厅空间，开放、宽敞，可能需要柱子支撑，作为主要活动区域";
        }
        if (lower.contains("走廊") || lower.contains("corridor") || lower.contains("过道")) {
            return "在此区域设计连接通道，确保能够连接不同区域，考虑通行便利";
        }
        if (lower.contains("楼梯") || lower.contains("stair") || lower.contains("阶梯")) {
            return "在此区域放置楼梯，连接不同楼层，考虑垂直交通的便利性";
        }
        if (lower.contains("阳台") || lower.contains("balcony") || lower.contains("露台")) {
            return "在此区域生成向外突出的阳台，包含栏杆，考虑视野和安全性";
        }
        if (lower.contains("花园") || lower.contains("garden") || lower.contains("园林")) {
            return "此区域作为花园，可以包含装饰性建筑元素（如亭子、拱门），保持开放感";
        }
        if (lower.contains("广场") || lower.contains("plaza") || lower.contains("空地")) {
            return "此区域作为广场，不建造主要建筑，或只包含装饰性建筑元素";
        }
        if (lower.contains("停车场") || lower.contains("parking")) {
            return "此区域作为停车场，保持平坦，可能有简单的结构或标识";
        }
        if (lower.contains("防御") || lower.contains("defensive") || lower.contains("守卫")) {
            return "在此区域生成防御性建筑元素，如围墙、瞭望塔、防御工事等";
        }
        if (lower.contains("神圣") || lower.contains("sacred") || lower.contains("宗教") || lower.contains("temple") || lower.contains("庙")) {
            return "在此区域生成宗教建筑，包含装饰性元素、对称布局、庄重的建筑风格";
        }
        if (lower.contains("公共") || lower.contains("public")) {
            return "在此区域生成公共建筑，开放、宽敞，便于集会和公共活动";
        }
        if (lower.contains("私人") || lower.contains("private")) {
            return "在此区域生成私密空间，需要围合、较少窗户或遮挡，保护隐私";
        }
        
        // 默认指导
        return "根据标签名称的含义，在该区域生成相应的建筑元素，确保建筑功能与标签语义匹配";
    }
}


