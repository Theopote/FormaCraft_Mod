package com.formacraft.common.component.placement;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentPlacementAnalyzerTest {

  @Test
  void balconyGeometryPrefersExteriorWallSurface() {
    ComponentDefinition def = new ComponentDefinition();
    def.category = ComponentCategory.GENERIC;
    def.tags = List.of("wood");
    def.size = size(5, 3, 4);
    def.blocks = balconyBlocks();

    ComponentPlacementSpec spec = ComponentPlacementAnalyzer.analyze(def, PlacementCaptureContext.createDefault());

    assertEquals(AttachmentType.WALL_SURFACE, spec.attachment);
    assertEquals(SpatialContext.EXTERIOR, spec.spatialContext);
    assertTrue(spec.requireExterior);
    assertTrue(spec.constraints.forbidInterior);
    assertTrue(spec.semanticTags.contains("railing"));
    assertTrue(spec.semanticTags.contains("composite"));
  }

  @Test
  void semanticRailingPartsBoostCompositeBalcony() {
    ComponentDefinition def = new ComponentDefinition();
    def.category = ComponentCategory.GENERIC;
    def.size = size(5, 3, 4);
    List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>();
    for (ComponentDefinition.BlockEntry base : balconyBlocks()) {
      ComponentDefinition.BlockEntry copy = block(base.dx, base.dy, base.dz);
      copy.block = base.block;
      if (base.dy == 1 && base.dz == 3 && base.dx > 0) {
        copy.block = "minecraft:oak_fence";
        copy.semantic = com.formacraft.common.semantic.SemanticPart.RAILING;
      }
      blocks.add(copy);
    }
    def.blocks = blocks;

    ComponentPlacementSpec spec = ComponentPlacementAnalyzer.analyze(def, PlacementCaptureContext.createDefault());
    assertEquals(AttachmentType.WALL_SURFACE, spec.attachment);
    assertTrue(spec.semanticTags.contains("composite"));
    assertTrue(spec.semanticTags.contains("railing"));
  }

  @Test
  void linearRailingPrefersEdge() {
    ComponentDefinition def = new ComponentDefinition();
    def.category = ComponentCategory.GENERIC;
    def.size = size(8, 2, 1);
    def.blocks = railingBlocks(8, 2);

    ComponentPlacementSpec spec = ComponentPlacementAnalyzer.analyze(def, PlacementCaptureContext.createDefault());

    assertEquals(AttachmentType.EDGE, spec.attachment);
    assertEquals(FacingPolicy.ALONG_EDGE, spec.facingPolicy);
    assertTrue(spec.constraints.requiresEdge);
  }

  @Test
  void manualAttachmentOverridesGeometry() {
    ComponentDefinition def = new ComponentDefinition();
    def.category = ComponentCategory.GENERIC;
    def.size = size(8, 2, 1);
    def.blocks = railingBlocks(8, 2);

    PlacementCaptureContext ctx = PlacementCaptureContext.createDefault();
    ctx.userAttachmentManual = true;
    ctx.userAttachment = AttachmentType.FLOOR;

    ComponentPlacementSpec spec = ComponentPlacementAnalyzer.analyze(def, ctx);
    assertEquals(AttachmentType.FLOOR, spec.attachment);
  }

  private static ComponentDefinition.Size size(int w, int h, int d) {
    ComponentDefinition.Size size = new ComponentDefinition.Size();
    size.w = w;
    size.h = h;
    size.d = d;
    return size;
  }

  private static List<ComponentDefinition.BlockEntry> balconyBlocks() {
    List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>();
    // back wall (x = 0)
    for (int y = 0; y < 3; y++) {
      for (int z = 0; z < 4; z++) {
        blocks.add(block(0, y, z));
      }
    }
    // floor slab
    for (int x = 1; x < 5; x++) {
      for (int z = 0; z < 4; z++) {
        blocks.add(block(x, 0, z));
      }
    }
    // outer railing line
    for (int x = 1; x < 5; x++) {
      blocks.add(block(x, 1, 3));
    }
    return blocks;
  }

  private static List<ComponentDefinition.BlockEntry> railingBlocks(int span, int height) {
    List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>();
    for (int x = 0; x < span; x++) {
      for (int y = 0; y < height; y++) {
        blocks.add(block(x, y, 0));
      }
    }
    return blocks;
  }

  private static ComponentDefinition.BlockEntry block(int x, int y, int z) {
    ComponentDefinition.BlockEntry entry = new ComponentDefinition.BlockEntry();
    entry.dx = x;
    entry.dy = y;
    entry.dz = z;
    entry.block = "minecraft:oak_planks";
    return entry;
  }
}
