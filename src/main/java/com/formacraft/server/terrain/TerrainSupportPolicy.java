package com.formacraft.server.terrain;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import java.util.Locale;
import java.util.regex.Pattern;

/** User choices apply to automatic terrain work, not authored building components. */
public record TerrainSupportPolicy(Mode mode, boolean access, BlockState material) {
    public enum Mode { AUTO, VERTICAL, DIAGONAL, SOLID }
    public static TerrainSupportPolicy automatic() { return new TerrainSupportPolicy(Mode.AUTO,false,null); }

    public static TerrainSupportPolicy fromRequest(FormaRequest request) {
        if(request==null) return automatic();
        String text=request.getUserMessage();
        if(text==null||text.isBlank()) text=request.getRequestText();
        if(text==null) return automatic();
        text=text.replaceAll("\\s+","").replace("垂直的","垂直").toLowerCase(Locale.ROOT);
        Mode mode=Mode.AUTO;
        if(Pattern.compile("(使用|采用|设置|用)(垂直支撑柱|垂直柱|垂直支柱|竖直支柱)|(不要|不用|不使用|不采用)(使用|采用)?斜撑").matcher(text).find()) mode=Mode.VERTICAL;
        else if(Pattern.compile("(使用|采用|设置|用)斜撑").matcher(text).find()) mode=Mode.DIAGONAL;
        else if(Pattern.compile("(使用|采用|设置|用)实心地基|填实地基").matcher(text).find()) mode=Mode.SOLID;
        boolean access=Pattern.compile("入口.{0,12}(连接|接入|接地)|门口.{0,8}(楼梯|台阶)|入口.{0,8}(楼梯|台阶|栈道)").matcher(text).find();
        if(Pattern.compile("(不需要|不要|不生成|不用).{0,8}(道路|通路|入口台阶|门口楼梯)|通路后补|入口连接后补").matcher(text).find()) access=false;
        BlockState material=null;
        var match=Pattern.compile("(?:支撑柱|支柱|斜撑|支撑)(?:材料)?(?:使用|采用|用|为)(石砖|圆石|石头|云杉木板|橡木木板|minecraft:[a-z0-9_]+)").matcher(text);
        if(match.find()) {
            String name=switch(match.group(1)) {
                case "石砖"->"minecraft:stone_bricks";case "圆石"->"minecraft:cobblestone";
                case "石头"->"minecraft:stone";case "云杉木板"->"minecraft:spruce_planks";
                case "橡木木板"->"minecraft:oak_planks";default->match.group(1);
            };
            material=BlockPatchTargetResolver.parse(DynamicPaletteResolver.mapMaterialToBlock(name));
            if(material==null||material.isAir()||!material.getFluidState().isEmpty())
                throw new IllegalArgumentException("无法使用指定的支撑材料："+match.group(1));
        }
        return new TerrainSupportPolicy(mode,access,material);
    }
}
