package com.formacraft.common.component.placement;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.semantic.SemanticPart;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 根据构件真实几何与捕获上下文推断 {@link ComponentPlacementSpec}。
 * <p>
 * 优先级（低 → 高）：
 * 1. 分类 / 标签基线
 * 2. 几何分析得分
 * 3. 方向标记（inside/outside、bottom/top）
 * 4. 用户手动附着（{@link PlacementCaptureContext#userAttachmentManual}）
 */
public final class ComponentPlacementAnalyzer {
  private ComponentPlacementAnalyzer() {}

  public static ComponentPlacementSpec analyze(ComponentDefinition def, PlacementCaptureContext ctx) {
    PlacementCaptureContext context = ctx != null ? ctx : PlacementCaptureContext.createDefault();
    ComponentPlacementSpec spec = baselineFromCategory(def != null ? def.category : null, def != null ? def.tags : null);
    GeometryProfile geo = GeometryProfile.from(def);

    if (geo != null) {
      applyGeometryInference(spec, geo, def);
    }

    applyOrientationHints(spec, context);
    applyManualAttachment(spec, context);
    spec.inferAllowedSockets();
    return spec;
  }

  /** 供 UI / Prompt 展示的简短放置分析摘要 */
  public static String formatSummary(ComponentPlacementSpec spec) {
    if (spec == null) {
      return "（待分析）";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(spec.attachment != null ? spec.attachment.name() : "NONE");
    if (spec.spatialContext != null && spec.spatialContext != SpatialContext.ANY) {
      sb.append(" | ").append(spec.spatialContext.name());
    }
    if (spec.facingPolicy != null && spec.facingPolicy != FacingPolicy.NONE) {
      sb.append(" | ").append(spec.facingPolicy.name());
    }
    if (spec.hasInteriorExterior) {
      sb.append(" | 内外");
    }
    if (spec.requireExterior) {
      sb.append(" | 需外侧");
    }
    if (spec.requireEdge) {
      sb.append(" | 需边缘");
    }
    if (spec.constraints != null && spec.constraints.requiresSupportBelow) {
      sb.append(" | 需下部支撑");
    }
    return sb.toString();
  }

  private static void applyGeometryInference(ComponentPlacementSpec spec, GeometryProfile geo, ComponentDefinition def) {
    double opening = scoreWallOpening(geo, def);
    double edge = scoreEdge(geo, def);
    double wallSurface = scoreWallSurface(geo, def);
    double floor = scoreFloor(geo, def);
    double roofSurface = scoreRoofSurface(geo, def);
    double roofEdge = scoreRoofEdge(geo, def);

    AttachmentChoice best = AttachmentChoice.NONE;
    double bestScore = 0.5;

    if (opening > bestScore) {
      best = AttachmentChoice.WALL_OPENING;
      bestScore = opening;
    }
    if (edge > bestScore) {
      best = AttachmentChoice.EDGE;
      bestScore = edge;
    }
    if (wallSurface > bestScore) {
      best = AttachmentChoice.WALL_SURFACE;
      bestScore = wallSurface;
    }
    if (floor > bestScore) {
      best = AttachmentChoice.FLOOR;
      bestScore = floor;
    }
    if (roofSurface > bestScore) {
      best = AttachmentChoice.ROOF_SURFACE;
      bestScore = roofSurface;
    }
    if (roofEdge > bestScore) {
      best = AttachmentChoice.ROOF_EDGE;
      bestScore = roofEdge;
    }

    if (best == AttachmentChoice.NONE) {
      return;
    }

    switch (best) {
      case WALL_OPENING -> applyWallOpening(spec, geo);
      case EDGE -> applyEdge(spec, geo);
      case WALL_SURFACE -> applyWallSurface(spec, geo, def);
      case FLOOR -> applyFloor(spec, geo);
      case ROOF_SURFACE -> applyRoofSurface(spec);
      case ROOF_EDGE -> applyRoofEdge(spec);
      default -> {
      }
    }
  }

  private static double scoreWallOpening(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    ComponentCategory cat = def != null && def.category != null ? def.category : ComponentCategory.GENERIC;
    if (cat == ComponentCategory.DOOR || cat == ComponentCategory.WINDOW || cat == ComponentCategory.ARCH) {
      score += 55;
    }
    if (geo.hasDoorWindowBlocks) {
      score += 45;
    }
    if (geo.hollowCenter && geo.height >= 2) {
      score += 25;
    }
    if (hasTag(def, "door", "window", "opening", "portal", "门", "窗")) {
      score += 20;
    }
    return score;
  }

  private static double scoreEdge(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    if (geo.isLinearEdge()) {
      score += 50;
    }
    if (hasTag(def, "railing", "guard", "balustrade", "栏杆", "护栏")) {
      score += 35;
    }
    if (geo.railingSemanticCount >= 3 && !geo.suggestsBalconyComposite()) {
      score += 20;
    }
    if (geo.height <= 3 && geo.aspectRatioXZ >= 2.5) {
      score += 20;
    }
    if (geo.suggestsBalconyComposite()) {
      score -= 45;
    }
    return score;
  }

  private static double scoreWallSurface(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    if (geo.suggestsBalcony()) {
      score += 50;
    }
    if (geo.suggestsBalconyComposite()) {
      score += 30;
    }
    if (geo.dominantFaceDensity >= 0.30) {
      score += 30;
    }
    if (geo.wallSemanticCount >= 4) {
      score += 15;
    }
    if (hasTag(def, "balcony", "terrace", "awning", "canopy", "阳台", "雨棚")) {
      score += 35;
    }
    if (def != null && def.category == ComponentCategory.ORNAMENT && geo.dominantFaceDensity >= 0.20) {
      score += 10;
    }
    return score;
  }

  private static double scoreFloor(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    if (geo.suggestsFloorMount()) {
      score += 45;
    }
    if (geo.bottomCoverage >= 0.55) {
      score += 20;
    }
    ComponentCategory cat = def != null && def.category != null ? def.category : ComponentCategory.GENERIC;
    if (cat == ComponentCategory.COLUMN || cat == ComponentCategory.STAIRS) {
      score += 25;
    }
    if (geo.dominantFaceDensity < 0.15 && geo.aspectRatioXZ < 2.0) {
      score += 15;
    }
    return score;
  }

  private static double scoreRoofSurface(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    if (hasTag(def, "chimney", "dormer", "烟囱", "老虎窗")) {
      score += 45;
    }
    if (geo.topCoverage > geo.bottomCoverage + 0.15) {
      score += 20;
    }
    return score;
  }

  private static double scoreRoofEdge(GeometryProfile geo, ComponentDefinition def) {
    double score = 0;
    if (def != null && def.category == ComponentCategory.ROOF_DETAIL) {
      score += 45;
    }
    if (hasTag(def, "eave", "flying_eave", "檐", "飞檐")) {
      score += 35;
    }
    if (geo.height <= 3 && geo.aspectRatioXZ >= 2.0 && geo.topCoverage >= 0.25) {
      score += 15;
    }
    return score;
  }

  private static void applyWallOpening(ComponentPlacementSpec spec, GeometryProfile geo) {
    spec.attachment = AttachmentType.WALL_OPENING;
    spec.spatialContext = SpatialContext.ANY;
    spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
    spec.hasInteriorExterior = true;
    spec.constraints.requiresAttachment = true;
    spec.constraints.minAttachments = 1;
    spec.constraints.maxAttachments = 1;
    spec.semanticTags.add("opening");
    spec.aiHint = "Geometry: hollow opening / door-window pattern detected.";
  }

  private static void applyEdge(ComponentPlacementSpec spec, GeometryProfile geo) {
    spec.attachment = AttachmentType.EDGE;
    spec.spatialContext = SpatialContext.EXTERIOR;
    spec.facingPolicy = FacingPolicy.ALONG_EDGE;
    spec.constraints.requiresAttachment = true;
    spec.constraints.requiresEdge = true;
    spec.constraints.prefersContinuity = true;
    spec.requireEdge = true;
    spec.semanticTags.add("edge");
    spec.semanticTags.add("safety");
    spec.aiHint = "Geometry: linear edge/railing span detected.";
  }

  private static void applyWallSurface(ComponentPlacementSpec spec, GeometryProfile geo, ComponentDefinition def) {
    spec.attachment = AttachmentType.WALL_SURFACE;
    spec.spatialContext = SpatialContext.EXTERIOR;
    spec.facingPolicy = FacingPolicy.OUTWARD_NORMAL;
    spec.constraints.requiresAttachment = true;
    spec.constraints.minAttachments = 1;
    spec.constraints.maxAttachments = geo.suggestsBalcony() ? 2 : 1;
    if (geo.suggestsBalcony() || hasTag(def, "balcony", "terrace", "阳台")) {
      spec.constraints.forbidInterior = true;
      spec.requireExterior = true;
      spec.semanticTags.add("balcony");
      spec.semanticTags.add("outdoor");
      spec.aiHint = "Geometry: balcony/terrace — wall-backed exterior volume with partial floor.";
      if (geo.suggestsBalconyComposite()) {
        spec.semanticTags.add("railing");
        spec.semanticTags.add("composite");
        spec.constraints.prefersContinuity = true;
        spec.aiHint += " Composite with outer edge guard/railing.";
      }
    } else {
      spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
      spec.semanticTags.add("surface_mount");
      spec.aiHint = "Geometry: dominant back plane suggests wall-surface attachment.";
    }
  }

  private static void applyFloor(ComponentPlacementSpec spec, GeometryProfile geo) {
    spec.attachment = AttachmentType.FLOOR;
    spec.spatialContext = SpatialContext.ANY;
    spec.facingPolicy = FacingPolicy.NONE;
    spec.constraints.requiresSupportBelow = true;
    spec.semanticTags.add("floor_mount");
    spec.aiHint = "Geometry: high bottom coverage, weak vertical back plane — floor/free standing.";
  }

  private static void applyRoofSurface(ComponentPlacementSpec spec) {
    spec.attachment = AttachmentType.ROOF_SURFACE;
    spec.spatialContext = SpatialContext.EXTERIOR;
    spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
    spec.constraints.requiresAttachment = true;
    spec.semanticTags.add("roof");
    spec.aiHint = "Geometry/tags: roof-mounted element.";
  }

  private static void applyRoofEdge(ComponentPlacementSpec spec) {
    spec.attachment = AttachmentType.ROOF_EDGE;
    spec.spatialContext = SpatialContext.EXTERIOR;
    spec.facingPolicy = FacingPolicy.ALONG_EDGE;
    spec.constraints.requiresAttachment = true;
    spec.constraints.requiresEdge = true;
    spec.semanticTags.add("roof");
    spec.semanticTags.add("detail");
    spec.aiHint = "Geometry/tags: roof edge detail.";
  }

  private static void applyOrientationHints(ComponentPlacementSpec spec, PlacementCaptureContext ctx) {
    if (ctx.hasInteriorExterior || ctx.hasInsideOutsideMarks) {
      spec.hasInteriorExterior = true;
      if (spec.facingPolicy == FacingPolicy.NONE) {
        spec.facingPolicy = switch (spec.attachment) {
          case WALL_OPENING, WALL_SURFACE, ROOF_SURFACE, ROOF_EDGE, EDGE, CORNER -> FacingPolicy.DERIVED_FROM_HOST;
          default -> spec.facingPolicy;
        };
      }
    }
    if (ctx.hasBottomTop || ctx.hasBottomTopMarks) {
      if (spec.facingPolicy == FacingPolicy.NONE) {
        spec.facingPolicy = FacingPolicy.USER_DEFINED;
      }
      spec.semanticTags.add("vertical_axis");
    }
    if (ctx.hasHostFace && spec.facingPolicy == FacingPolicy.NONE) {
      spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
    }
  }

  private static void applyManualAttachment(ComponentPlacementSpec spec, PlacementCaptureContext ctx) {
    if (!ctx.userAttachmentManual || ctx.userAttachment == null) {
      return;
    }
    spec.attachment = ctx.userAttachment;
    if (ctx.userAttachment != AttachmentType.NONE) {
      spec.constraints.requiresAttachment = true;
    }
    if (spec.aiHint == null || spec.aiHint.isBlank()) {
      spec.aiHint = "User-specified attachment: " + ctx.userAttachment.name();
    } else {
      spec.aiHint = "User attachment override (" + ctx.userAttachment.name() + "); " + spec.aiHint;
    }
  }

  private static ComponentPlacementSpec baselineFromCategory(ComponentCategory category, List<String> tags) {
    ComponentCategory c = category != null ? category : ComponentCategory.GENERIC;
    ComponentPlacementSpec spec = new ComponentPlacementSpec();
    spec.attachment = AttachmentType.NONE;
    spec.spatialContext = SpatialContext.ANY;
    spec.facingPolicy = FacingPolicy.NONE;
    spec.constraints = new PlacementConstraints();

    switch (c) {
      case DOOR -> {
        spec.semanticTags.add("entry");
        spec.semanticTags.add("circulation");
      }
      case WINDOW -> {
        spec.semanticTags.add("light");
        spec.semanticTags.add("ventilation");
      }
      case COLUMN -> spec.semanticTags.add("support");
      case BRACKET -> spec.semanticTags.add("transition");
      case ROOF_DETAIL -> spec.semanticTags.add("roof");
      case ORNAMENT -> spec.semanticTags.add("ornament");
      case ARCH -> spec.semanticTags.add("arch");
      case STAIRS -> spec.semanticTags.add("circulation");
      default -> {
      }
    }

    applyTagHints(spec, tags);
    return spec;
  }

  private static void applyTagHints(ComponentPlacementSpec spec, List<String> tags) {
    if (tags == null) {
      return;
    }
    for (String t : tags) {
      if (t == null) {
        continue;
      }
      String u = t.trim().toLowerCase(Locale.ROOT);
      if (u.contains("balcony") || u.contains("terrace") || u.contains("阳台")) {
        spec.semanticTags.add("balcony");
        spec.semanticTags.add("outdoor");
      }
      if (u.contains("railing") || u.contains("guard") || u.contains("栏杆")) {
        spec.semanticTags.add("safety");
      }
      if (u.contains("chimney") || u.contains("烟囱")) {
        spec.semanticTags.add("roof");
      }
    }
  }

  private static boolean hasTag(ComponentDefinition def, String... needles) {
    if (def == null || def.tags == null) {
      return false;
    }
    for (String tag : def.tags) {
      if (tag == null) {
        continue;
      }
      String lower = tag.toLowerCase(Locale.ROOT);
      for (String needle : needles) {
        if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
          return true;
        }
      }
    }
    return false;
  }

  private enum AttachmentChoice {
    NONE, WALL_OPENING, EDGE, WALL_SURFACE, FLOOR, ROOF_SURFACE, ROOF_EDGE
  }

  /**
   * 从构件方块分布提取几何特征。
   */
  static final class GeometryProfile {
    int width;
    int height;
    int depth;
    int blockCount;
    double fillRatio;
    double bottomCoverage;
    double topCoverage;
    double dominantFaceDensity;
    double aspectRatioXZ;
    boolean hasDoorWindowBlocks;
    boolean hollowCenter;
    boolean projectsBeyondDominantFace;
    int wallSemanticCount;
    int floorSemanticCount;
    int railingSemanticCount;
    int perimeterElevatedGuardCount;
    int boundsMinX;
    int boundsMinY;
    int boundsMaxX;
    int boundsMaxZ;
    int dominantFaceIndex;

    static GeometryProfile from(ComponentDefinition def) {
      if (def == null || def.blocks == null || def.blocks.isEmpty()) {
        return null;
      }

      GeometryProfile g = new GeometryProfile();
      int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      Set<Long> occupied = new HashSet<>();

      for (ComponentDefinition.BlockEntry block : def.blocks) {
        if (block == null) {
          continue;
        }
        minX = Math.min(minX, block.dx);
        minY = Math.min(minY, block.dy);
        minZ = Math.min(minZ, block.dz);
        maxX = Math.max(maxX, block.dx);
        maxY = Math.max(maxY, block.dy);
        maxZ = Math.max(maxZ, block.dz);
        occupied.add(pack(block.dx, block.dy, block.dz));
        SemanticPart part = block.semantic != null ? block.semantic : inferSemanticFromBlock(block.block);
        switch (part) {
          case WALL, TOWER_WALL, WALL_BASE, WALL_ACCENT, PILLAR, BEAM -> g.wallSemanticCount++;
          case FLOOR, WALKWAY_FLOOR, COURTYARD_FLOOR, FOUNDATION -> g.floorSemanticCount++;
          case RAILING, PATH_EDGE, ROAD_EDGE -> g.railingSemanticCount++;
          default -> {
          }
        }
        if (block.block != null) {
          String lower = block.block.toLowerCase(Locale.ROOT);
          if (lower.contains("door") || lower.contains("window") || lower.contains("glass_pane")) {
            g.hasDoorWindowBlocks = true;
          }
        }
      }

      if (occupied.isEmpty()) {
        return null;
      }

      g.boundsMinX = minX;
      g.boundsMinY = minY;
      g.boundsMaxX = maxX;
      g.boundsMaxZ = maxZ;

      for (long packed : occupied) {
        int dx = unpackX(packed);
        int dy = unpackY(packed);
        int dz = unpackZ(packed);
        if (dy <= minY) {
          continue;
        }
        boolean onOuterEdge = dx == maxX || dz == maxZ || dz == minZ;
        boolean onBackPlane = dx == minX;
        if (onOuterEdge && !onBackPlane) {
          g.perimeterElevatedGuardCount++;
        }
      }

      if (def.size != null && def.size.w > 0 && def.size.h > 0 && def.size.d > 0) {
        g.width = def.size.w;
        g.height = def.size.h;
        g.depth = def.size.d;
      } else {
        g.width = maxX - minX + 1;
        g.height = maxY - minY + 1;
        g.depth = maxZ - minZ + 1;
      }

      g.blockCount = occupied.size();
      int volume = Math.max(1, g.width * g.height * g.depth);
      g.fillRatio = (double) g.blockCount / volume;

      int bottomArea = Math.max(1, g.width * g.depth);
      int topArea = bottomArea;
      int bottomCount = 0;
      int topCount = 0;
      double[] faceDensity = new double[4];
      int[] faceArea = {
          Math.max(1, g.height * g.depth),
          Math.max(1, g.height * g.depth),
          Math.max(1, g.width * g.height),
          Math.max(1, g.width * g.height)
      };
      int[] faceCount = new int[4];

      for (long packed : occupied) {
        int dx = unpackX(packed);
        int dy = unpackY(packed);
        int dz = unpackZ(packed);
        if (dy == minY) {
          bottomCount++;
        }
        if (dy == maxY) {
          topCount++;
        }
        if (dx == minX) {
          faceCount[0]++;
        }
        if (dx == maxX) {
          faceCount[1]++;
        }
        if (dz == minZ) {
          faceCount[2]++;
        }
        if (dz == maxZ) {
          faceCount[3]++;
        }
      }

      g.bottomCoverage = (double) bottomCount / bottomArea;
      g.topCoverage = (double) topCount / topArea;

      double maxFace = 0;
      int dominantFaceIndex = 0;
      for (int i = 0; i < 4; i++) {
        faceDensity[i] = (double) faceCount[i] / faceArea[i];
        if (faceDensity[i] > maxFace) {
          maxFace = faceDensity[i];
          dominantFaceIndex = i;
        }
      }
      g.dominantFaceDensity = maxFace;
      g.dominantFaceIndex = dominantFaceIndex;

      int minHoriz = Math.min(g.width, g.depth);
      int maxHoriz = Math.max(g.width, g.depth);
      g.aspectRatioXZ = minHoriz == 0 ? 1.0 : (double) maxHoriz / minHoriz;

      g.projectsBeyondDominantFace = hasProjectionBeyondDominantFace(
              occupied, minX, maxX, minZ, maxZ, dominantFaceIndex);

      int interiorVolume = Math.max(0, (g.width - 2) * Math.max(0, g.height - 2) * Math.max(0, g.depth - 2));
      int interiorBlocks = 0;
      if (g.width >= 3 && g.height >= 3 && g.depth >= 3) {
        for (int x = minX + 1; x <= maxX - 1; x++) {
          for (int y = minY + 1; y <= maxY - 1; y++) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
              if (occupied.contains(pack(x, y, z))) {
                interiorBlocks++;
              }
            }
          }
        }
        g.hollowCenter = interiorVolume > 0 && ((double) interiorBlocks / interiorVolume) < 0.45;
      }

      return g;
    }

    boolean isLinearEdge() {
      int thin = Math.min(width, depth);
      int span = Math.max(width, depth);
      return height <= 3 && thin <= 2 && span >= 4 && aspectRatioXZ >= 2.0;
    }

    boolean suggestsBalcony() {
      return dominantFaceDensity >= 0.22
          && projectsBeyondDominantFace
          && fillRatio >= 0.12
          && fillRatio <= 0.75
          && height >= 2;
    }

    boolean suggestsBalconyComposite() {
      return suggestsBalcony()
          && (railingSemanticCount >= 2
              || perimeterElevatedGuardCount >= 2
              || (floorSemanticCount >= 4 && wallSemanticCount >= 4 && perimeterElevatedGuardCount >= 1));
    }

    boolean suggestsFloorMount() {
      return bottomCoverage >= 0.45 && dominantFaceDensity < 0.22 && aspectRatioXZ < 2.5;
    }

    private static SemanticPart inferSemanticFromBlock(String blockState) {
      if (blockState == null || blockState.isBlank()) {
        return SemanticPart.WALL;
      }
      String lower = blockState.toLowerCase(Locale.ROOT);
      if (lower.contains("fence") || lower.contains("iron_bars") || lower.contains("bars")) {
        return SemanticPart.RAILING;
      }
      if (lower.contains("slab") || lower.contains("planks")) {
        return SemanticPart.FLOOR;
      }
      if (lower.contains("stairs")) {
        return SemanticPart.STAIR_STEP;
      }
      if (lower.contains("door") || lower.contains("trapdoor")) {
        return SemanticPart.DOORWAY;
      }
      if (lower.contains("glass")) {
        return SemanticPart.WINDOW;
      }
      if (lower.contains("log") || lower.contains("pillar")) {
        return SemanticPart.PILLAR;
      }
      return SemanticPart.WALL;
    }

    private static long pack(int x, int y, int z) {
      return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
    }

    private static int unpackX(long packed) {
      return (int) (packed & 0xFFFFF);
    }

    private static int unpackY(long packed) {
      return (int) ((packed >> 20) & 0xFFFFF);
    }

    private static int unpackZ(long packed) {
      return (int) ((packed >> 40) & 0xFFFFF);
    }

    private static boolean hasProjectionBeyondDominantFace(
            Set<Long> occupied, int minX, int maxX, int minZ, int maxZ, int dominantFaceIndex) {
      for (long packed : occupied) {
        int dx = unpackX(packed);
        int dz = unpackZ(packed);
        switch (dominantFaceIndex) {
          case 0 -> {
            if (dx > minX) return true;
          }
          case 1 -> {
            if (dx < maxX) return true;
          }
          case 2 -> {
            if (dz > minZ) return true;
          }
          case 3 -> {
            if (dz < maxZ) return true;
          }
          default -> {
          }
        }
      }
      return false;
    }
  }
}
