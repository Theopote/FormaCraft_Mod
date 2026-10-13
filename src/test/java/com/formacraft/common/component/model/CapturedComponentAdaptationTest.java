package com.formacraft.common.component.model;

import com.formacraft.common.component.*;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.variant.*;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapturedComponentAdaptationTest {
    private ComponentDefinition column() {
        var def=new ComponentDefinition(); def.id="captured_column"; def.category=ComponentCategory.COLUMN;
        def.size=new ComponentDefinition.Size(); def.size.w=1;def.size.h=5;def.size.d=1;
        def.blocks=new ArrayList<>();
        for(int y=0;y<5;y++) {var b=new ComponentDefinition.BlockEntry(); b.dy=y;b.block="minecraft:stone";def.blocks.add(b);}
        var settings=new CaptureAdaptationSettings();settings.modes[1]=CaptureAdaptationSettings.Mode.REPEAT;
        def.variantRules=settings.toRules(def);return def;
    }
    private ComponentQuery query(int height) {var q=new ComponentQuery();q.geometry.openingHeight=height;return q;}
    @Test void explicitFitDoesNotApplyGlobalScalingTwice() {
        var base=column();var legacy=new ComponentVariant(base);legacy.applyScale(3,AxisScalePolicy.UNIFORM);
        legacy.applyTrim(1,3,1);
        var out=ComponentModelApi.fitToQuery(base,query(8),legacy);
        assertNotNull(out);assertEquals(8,out.size.h);assertEquals(8,out.blocks.size());
        assertEquals(5,base.blocks.size());assertEquals(5,base.size.h);
    }
    @Test void fixedAxesCannotBeBypassedByLegacyScale() {
        var base=column();var q=query(5);q.geometry.openingWidth=2;
        var v=new ComponentVariant(base);v.applyScale(2,AxisScalePolicy.UNIFORM);
        assertNull(ComponentModelApi.fitToQuery(base,q,v));
    }
    @Test void impossibleShrinkIsRejected() {assertNull(ComponentModelApi.fitToQuery(column(),query(2),null));}
    @Test void safeShrinkPreservesTheTopCap() {
        var base=column();base.blocks.getLast().block="minecraft:gold_block";
        var out=ComponentModelApi.fitToQuery(base,query(3),null);assertNotNull(out);
        assertEquals("minecraft:gold_block",out.blocks.stream().filter(b->b.dy==2).findFirst().orElseThrow().block);
    }
    @Test void geometryScalableFalseLocksAllAxes() {
        var q=query(8);q.geometry.scalable=false;
        assertNull(ComponentModelApi.fitToQuery(column(),q,null));
    }
    @Test void nullQueryDoesNotRandomlyAlterCapture() {
        var base=column();assertSame(base,ComponentModelApi.applyRuntimeVariant(base,null,new ComponentVariant(base)));
        var v=VariantGenerator.generate(base,new ComponentQuery(),new Random(1));assertEquals(1,v.scaleY);
    }
    @Test void metadataMovesWithGeometryWithoutMutatingOriginal() {
        var base=column();base.directionHints=new ComponentDefinition.DirectionHints();
        base.directionHints.top=new ComponentDefinition.DirectionHints.Mark();base.directionHints.top.dy=4;
        var socket=new ComponentDefinition.SocketPlacement();socket.id="top";socket.dy=4;socket.facing="SOUTH";
        base.socketPlacements=List.of(socket);
        var out=ComponentModelApi.fitToQuery(base,query(8),null);
        assertNotNull(out);assertEquals(7,out.directionHints.top.dy);assertEquals(7,out.socketPlacements.getFirst().dy);
        assertEquals(4,base.directionHints.top.dy);assertEquals(4,socket.dy);
    }
    @Test void authorRulesSurviveJsonAndRuntimePrototypeBridge() {
        var base=column();var decoded=JsonUtil.fromJson(JsonUtil.toJson(base),ComponentDefinition.class);
        var proto=RuntimeComponentPrototypeFactory.fromDefinition(decoded);
        assertEquals("FIXED",proto.variant_rules.scaling.axes.get("X").type);
        assertEquals("REPEAT",proto.variant_rules.scaling.axes.get("Y").type);
        assertNull(proto.variant_rules.material);
        assertEquals(1,proto.variant_rules.scaling.axes.get("Y").fixedStart);
    }
    @Test void tolerancePrefersUnmodifiedOriginal() {
        var base=column();var q=query(6);q.geometry.tolerance=1;
        assertSame(base,ComponentModelApi.fitToQuery(base,q,null));
    }
    @Test void nearestPermittedSizeMayFitTolerance() {
        var base=column();base.variantRules.scaling.axes.get("Y").max=7;
        var q=query(8);q.geometry.tolerance=1;
        var out=ComponentModelApi.fitToQuery(base,q,null);assertNotNull(out);assertEquals(7,out.size.h);
    }
    @Test void invalidZeroDimensionsAreRejected() {
        assertNull(ComponentModelApi.fitToQuery(column(),query(0),null));
    }
    @Test void windowFrameRetainsFourSidesAfterShrinking() {
        var base=column();base.category=ComponentCategory.WINDOW;
        base.size.w=5;base.size.h=6;base.size.d=1;base.blocks=new ArrayList<>();
        for(int x=0;x<5;x++) for(int y=0;y<6;y++) {
            var b=new ComponentDefinition.BlockEntry();b.dx=x;b.dy=y;
            b.block=x==0||x==4||y==0||y==5?"minecraft:stone_bricks":"minecraft:glass";
            base.blocks.add(b);
        }
        var settings=new CaptureAdaptationSettings();settings.modes[0]=settings.modes[1]=CaptureAdaptationSettings.Mode.REPEAT;
        base.variantRules=settings.toRules(base);
        var q=query(4);q.geometry.openingWidth=3;
        var out=ComponentModelApi.fitToQuery(base,q,null);assertNotNull(out);assertEquals(12,out.blocks.size());
        for(var b:out.blocks) assertEquals(b.dx==0||b.dx==2||b.dy==0||b.dy==3?
                "minecraft:stone_bricks":"minecraft:glass",b.block);
    }
    @Test void thinWindowCannotLoseItsOpening() {
        var base=column();base.size.w=3;base.size.h=6;base.size.d=1;base.blocks=new ArrayList<>();
        for(int x=0;x<3;x++) for(int y=0;y<6;y++) {
            var b=new ComponentDefinition.BlockEntry();b.dx=x;b.dy=y;b.block="minecraft:stone";base.blocks.add(b);
        }
        var settings=new CaptureAdaptationSettings();settings.modes[0]=settings.modes[1]=CaptureAdaptationSettings.Mode.REPEAT;
        base.variantRules=settings.toRules(base);
        var q=query(4);q.geometry.openingWidth=2;
        assertNull(ComponentModelApi.fitToQuery(base,q,null));
    }
    @Test void invalidCapThicknessIsReportedBeforeSave() {
        var base=column();base.variantRules.scaling.axes.get("Y").fixedStart=4;
        assertNotNull(CapturedComponentRuleValidator.validate(base));
    }
}
