package com.formacraft.server.assembly;

import java.util.List;

/**
 * 2D XZ routing utilities (A* detours, lead-out/lead-in geometry).
 */
final class AssemblyConnectionRouter {
    private static final int[][] DIR4 = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

    private AssemblyConnectionRouter() {}

    record Rect2(int xMin, int zMin, int xMax, int zMax) {}
    static List<int[]> computeDetour(int[] a,
                                             int[] b,
                                             List<Rect2> avoids,
                                             boolean useAStar,
                                             int routingPad,
                                             long routingMaxArea,
                                             int routingMaxNodes,
                                             int preferStraight,
                                             String preferAxis,
                                             int preferAxisWeight,
                                             int leadOut,
                                             int leadIn,
                                             String fromPort,
                                             String toPort,
                                             int leadOutWeight,
                                             int leadInWeight,
                                             String leadRing,
                                             int leadInStepsMaxNodes) {
        // If segment doesn't intersect any avoid, keep it.
        boolean hit = false;
        for (Rect2 r : avoids) {
            if (segmentIntersectsRect(a[0], a[2], b[0], b[2], r)) { hit = true; break; }
        }
        if (!hit) return List.of(b);

        if (useAStar) {
            List<int[]> path = routeAStar2D(a, b, avoids, routingPad, routingMaxArea, routingMaxNodes, preferStraight, preferAxis, preferAxisWeight,
                    leadOut, leadIn, fromPort, toPort, leadOutWeight, leadInWeight, leadRing, leadInStepsMaxNodes);
            if (path != null && !path.isEmpty()) return path;
        }

        // Try detouring around each rect; pick best candidate that avoids all rects.
        List<int[]> best = null;
        long bestCost = Long.MAX_VALUE;

        for (Rect2 r : avoids) {
            List<List<int[]>> candidates = detourCandidates(a, b, r);
            for (List<int[]> cand : candidates) {
                // validate each segment in candidate chain against all avoids
                int[] prev = a;
                boolean ok = true;
                for (int[] p : cand) {
                    for (Rect2 rr : avoids) {
                        if (segmentIntersectsRect(prev[0], prev[2], p[0], p[2], rr)) { ok = false; break; }
                    }
                    if (!ok) break;
                    prev = p;
                }
                if (!ok) continue;

                long cost = 0;
                prev = a;
                for (int[] p : cand) {
                    cost += manhattan2(prev, p);
                    prev = p;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = cand;
                }
            }
        }

        return best != null ? best : List.of(b);
    }

    /**
     * 2D grid A* routing in XZ, returns a compressed waypoint list (excluding start, including end).
     * Safety caps:
     * - if bounding box too large, return null to fall back to heuristic detours.
     */
    static List<int[]> routeAStar2D(int[] a,
                                            int[] b,
                                            List<Rect2> avoids,
                                            int pad,
                                            long maxArea,
                                            int maxNodesOverride,
                                            int turnPenalty,
                                            String preferAxis,
                                            int preferAxisWeight,
                                            int leadOut,
                                            int leadIn,
                                            String fromPort,
                                            String toPort,
                                            int leadOutWeight,
                                            int leadInWeight,
                                            String leadRing,
                                            int leadInStepsMaxNodes) {
        // Bounds: endpoints + avoid rects + padding
        int minX = Math.min(a[0], b[0]);
        int maxX = Math.max(a[0], b[0]);
        int minZ = Math.min(a[2], b[2]);
        int maxZ = Math.max(a[2], b[2]);
        for (Rect2 r : avoids) {
            minX = Math.min(minX, r.xMin);
            maxX = Math.max(maxX, r.xMax);
            minZ = Math.min(minZ, r.zMin);
            maxZ = Math.max(maxZ, r.zMax);
        }
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;

        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        long area = (long) w * (long) h;
        // hard cap to avoid runaway compile time
        if (area > maxArea) return null;

        int y = (a[1] != 0) ? a[1] : b[1];
        int[] outDir = (leadOut > 0) ? dirFromPort(fromPort) : null; // [dx,dz] outward
        int[] inDir = (leadIn > 0) ? dirFromPort(toPort) : null;     // outward; approach wants opposite
        if (leadOutWeight < 0) leadOutWeight = 0;
        if (leadInWeight < 0) leadInWeight = 0;

        long start = pack(a[0], a[2]);
        long goal = pack(b[0], b[2]);
        if (isBlocked(a[0], a[2], avoids) || isBlocked(b[0], b[2], avoids)) return null;

        java.util.HashMap<Long, Long> came = new java.util.HashMap<>();
        java.util.HashMap<Long, Integer> gScore = new java.util.HashMap<>();
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>();

        gScore.put(start, 0);
        open.add(new Node(start, 0, heuristic(a[0], a[2], b[0], b[2])));

        int maxNodes = maxNodesOverride > 0
                ? maxNodesOverride
                : (int) Math.min(80000L, Math.max(12000L, area)); // scale with area but capped
        int visited = 0;

        boolean useStepRing = !"MANHATTAN".equalsIgnoreCase(leadRing);
        int[] distToGoalSteps = null;
        if (useStepRing && leadIn > 0 && inDir != null) {
            // Reverse BFS from goal to get true remaining steps to goal within this bounded grid.
            // This avoids Manhattan under-estimating distances when detours are required.
            if (leadInStepsMaxNodes < 0) leadInStepsMaxNodes = 0;
            int bfsMaxNodes = (leadInStepsMaxNodes > 0) ? Math.min(maxNodes, Math.max(2000, leadInStepsMaxNodes)) : maxNodes;
            distToGoalSteps = computeDistToGoalSteps(minX, minZ, w, h, b[0], b[2], avoids, bfsMaxNodes);
        }

        while (!open.isEmpty() && visited < maxNodes) {
            Node cur = open.poll();
            long key = cur.key;
            if (key == goal) {
                return reconstructCompressed(came, start, goal, y);
            }
            visited++;

            int cx = unpackX(key);
            int cz = unpackZ(key);
            int baseG = gScore.getOrDefault(key, Integer.MAX_VALUE / 4);

            // 4-neighbors
            for (int[] d : DIR4) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                if (isBlocked(nx, nz, avoids)) continue;
                long nk = pack(nx, nz);
                int penalty = 0;
                if (turnPenalty > 0) {
                    Long parentKey = came.get(key);
                    if (parentKey != null) {
                        int px = unpackX(parentKey);
                        int pz = unpackZ(parentKey);
                        int dx0 = Integer.compare(cx - px, 0);
                        int dz0 = Integer.compare(cz - pz, 0);
                        int dx1 = Integer.compare(nx - cx, 0);
                        int dz1 = Integer.compare(nz - cz, 0);
                        boolean isTurn = (dx0 != dx1) || (dz0 != dz1);
                        if (isTurn) penalty = turnPenalty;
                    }
                }

                // Prefer axis: add a small penalty when moving against preferred axis.
                int axisPenalty = 0;
                String ax = (d[0] != 0) ? "X" : "Z";
                String pref = (preferAxis == null || preferAxis.isBlank()) ? "AUTO" : preferAxis;
                if (preferAxisWeight > 0) {
                    if (pref.equals("AUTO")) {
                        int adx = Math.abs(b[0] - a[0]);
                        int adz = Math.abs(b[2] - a[2]);
                        pref = (adx >= adz) ? "X" : "Z";
                    }
                    if (pref.equals("X") || pref.equals("Z")) {
                        if (!ax.equals(pref)) axisPenalty = preferAxisWeight;
                    }
                }
                int tentative = baseG + 1 + penalty;
                tentative += axisPenalty;

                // Soft lead-out: near start, penalize moves not matching outward port direction
                if (leadOut > 0 && outDir != null) {
                    int distFromStart = useStepRing ? baseG : (Math.abs(cx - a[0]) + Math.abs(cz - a[2]));
                    if (distFromStart < leadOut) {
                        if (d[0] != outDir[0] || d[1] != outDir[1]) tentative += leadOutWeight;
                    }
                }
                // Soft lead-in: near goal, penalize moves that don't approach along the opposite of the port outward dir
                if (leadIn > 0 && inDir != null) {
                    int distToGoal;
                    if (useStepRing && distToGoalSteps != null) {
                        int idx = (cx - minX) + (cz - minZ) * w;
                        if (idx >= 0 && idx < distToGoalSteps.length && distToGoalSteps[idx] >= 0) distToGoal = distToGoalSteps[idx];
                        else distToGoal = Math.abs(cx - b[0]) + Math.abs(cz - b[2]);
                    } else {
                        distToGoal = Math.abs(cx - b[0]) + Math.abs(cz - b[2]);
                    }
                    if (distToGoal < leadIn) {
                        int adx = -inDir[0];
                        int adz = -inDir[1];
                        if (d[0] != adx || d[1] != adz) tentative += leadInWeight;
                    }
                }
                int best = gScore.getOrDefault(nk, Integer.MAX_VALUE / 4);
                if (tentative < best) {
                    came.put(nk, key);
                    gScore.put(nk, tentative);
                    int f = tentative + heuristic(nx, nz, b[0], b[2]);
                    open.add(new Node(nk, tentative, f));
                }
            }
        }
        return null;
    }

    static int[] computeDistToGoalSteps(int minX, int minZ, int w, int h, int goalX, int goalZ, List<Rect2> avoids, int maxNodes) {
        int[] dist = new int[w * h];
        java.util.Arrays.fill(dist, -1);
        if (goalX < minX || goalX > (minX + w - 1) || goalZ < minZ || goalZ > (minZ + h - 1)) return dist;
        if (isBlocked(goalX, goalZ, avoids)) return dist;

        int gIdx = (goalX - minX) + (goalZ - minZ) * w;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        dist[gIdx] = 0;
        q.add(gIdx);
        int visited = 0;
        while (!q.isEmpty() && visited < maxNodes) {
            int idx = q.poll();
            int cx = (idx % w) + minX;
            int cz = (idx / w) + minZ;
            int cd = dist[idx];
            visited++;
            for (int[] d : DIR4) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < minX || nx > (minX + w - 1) || nz < minZ || nz > (minZ + h - 1)) continue;
                if (isBlocked(nx, nz, avoids)) continue;
                int nIdx = (nx - minX) + (nz - minZ) * w;
                if (dist[nIdx] != -1) continue;
                dist[nIdx] = cd + 1;
                q.add(nIdx);
            }
        }
        return dist;
    }

    static String axisFromPort(String port) {
        if (port == null) return null;
        String p = AssemblyCompilerUtils.normalizePortKey(port);
        // allow prefixed directional ports like start_east / end_north / entrance_south
        String base = p;
        int idx = p.lastIndexOf('_');
        if (idx >= 0 && idx + 1 < p.length()) base = p.substring(idx + 1);
        // directional ports imply axis
        if (base.equals("east") || base.equals("west") || base.equals("left") || base.equals("right")) return "X";
        if (base.equals("north") || base.equals("south") || base.equals("front") || base.equals("back") || base.equals("entrance") || base.equals("exit")) return "Z";
        // corners imply both; keep AUTO
        return null;
    }

    record Node(long key, int g, int f) implements Comparable<Node> {
        @Override public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }

    static int heuristic(int x0, int z0, int x1, int z1) {
        return Math.abs(x0 - x1) + Math.abs(z0 - z1);
    }

    static boolean isBlocked(int x, int z, List<Rect2> avoids) {
        for (Rect2 r : avoids) {
            if (x >= r.xMin && x <= r.xMax && z >= r.zMin && z <= r.zMax) return true;
        }
        return false;
    }

    static List<int[]> reconstructCompressed(java.util.HashMap<Long, Long> came, long start, long goal, int y) {
        java.util.ArrayList<long[]> rev = new java.util.ArrayList<>();
        long cur = goal;
        rev.add(new long[]{unpackX(cur), unpackZ(cur)});
        while (cur != start) {
            Long p = came.get(cur);
            if (p == null) return null;
            cur = p;
            rev.add(new long[]{unpackX(cur), unpackZ(cur)});
        }
        // reverse
        java.util.Collections.reverse(rev);
        // compress into turn points; skip the first point (start), include end
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int lastDx = 0, lastDz = 0;
        for (int i = 1; i < rev.size(); i++) {
            int x0 = (int) rev.get(i - 1)[0], z0 = (int) rev.get(i - 1)[1];
            int x1 = (int) rev.get(i)[0], z1 = (int) rev.get(i)[1];
            int dx = Integer.compare(x1 - x0, 0);
            int dz = Integer.compare(z1 - z0, 0);
            boolean turn = (i == 1) || (dx != lastDx) || (dz != lastDz);
            if (turn && i - 1 > 0) {
                // add the previous point as a waypoint
                out.add(new int[]{x0, y, z0});
            }
            lastDx = dx; lastDz = dz;
        }
        long[] end = rev.getLast();
        out.add(new int[]{(int) end[0], y, (int) end[1]});
        return out;
    }

    static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static int unpackX(long k) { return (int) (k >> 32); }
    static int unpackZ(long k) { return (int) k; }

    static List<List<int[]>> detourCandidates(int[] a, int[] b, Rect2 r) {
        int yA = a[1];
        int yB = b[1];
        int y = (yA != 0) ? yA : yB;

        // Corners around expanded rect (one block outside)
        int lx = r.xMin - 1, rx = r.xMax + 1;
        int nz = r.zMin - 1, sz = r.zMax + 1;

        // Candidate 1: go around left side (x=lx), then to b
        List<int[]> c1 = List.of(new int[]{lx, y, a[2]}, new int[]{lx, y, b[2]}, b);
        // Candidate 2: right side
        List<int[]> c2 = List.of(new int[]{rx, y, a[2]}, new int[]{rx, y, b[2]}, b);
        // Candidate 3: north side
        List<int[]> c3 = List.of(new int[]{a[0], y, nz}, new int[]{b[0], y, nz}, b);
        // Candidate 4: south side
        List<int[]> c4 = List.of(new int[]{a[0], y, sz}, new int[]{b[0], y, sz}, b);
        // Candidate 5-8: corner pivots (diagonal-ish L turns)
        List<int[]> c5 = List.of(new int[]{lx, y, nz}, b);
        List<int[]> c6 = List.of(new int[]{lx, y, sz}, b);
        List<int[]> c7 = List.of(new int[]{rx, y, nz}, b);
        List<int[]> c8 = List.of(new int[]{rx, y, sz}, b);

        return List.of(c1, c2, c3, c4, c5, c6, c7, c8);
    }

    static long manhattan2(int[] a, int[] b) {
        return (long) Math.abs(a[0] - b[0]) + (long) Math.abs(a[2] - b[2]);
    }

    // Segment-rect intersection in XZ (axis-aligned rect)
    static boolean segmentIntersectsRect(int x0, int z0, int x1, int z1, Rect2 r) {
        // Quick reject: both endpoints on one side
        if (x0 < r.xMin && x1 < r.xMin) return false;
        if (x0 > r.xMax && x1 > r.xMax) return false;
        if (z0 < r.zMin && z1 < r.zMin) return false;
        if (z0 > r.zMax && z1 > r.zMax) return false;

        // If either endpoint inside rect -> intersects
        if (x0 >= r.xMin && x0 <= r.xMax && z0 >= r.zMin && z0 <= r.zMax) return true;
        if (x1 >= r.xMin && x1 <= r.xMax && z1 >= r.zMin && z1 <= r.zMax) return true;

        // Check intersection with rectangle edges.
        int rx0 = r.xMin, rz0 = r.zMin, rx1 = r.xMax, rz1 = r.zMax;
        return segmentsIntersect(x0, z0, x1, z1, rx0, rz0, rx1, rz0) // north edge
                || segmentsIntersect(x0, z0, x1, z1, rx1, rz0, rx1, rz1) // east edge
                || segmentsIntersect(x0, z0, x1, z1, rx1, rz1, rx0, rz1) // south edge
                || segmentsIntersect(x0, z0, x1, z1, rx0, rz1, rx0, rz0); // west edge
    }

    static boolean segmentsIntersect(int ax, int az, int bx, int bz, int cx, int cz, int dx, int dz) {
        int o1 = orient(ax, az, bx, bz, cx, cz);
        int o2 = orient(ax, az, bx, bz, dx, dz);
        int o3 = orient(cx, cz, dx, dz, ax, az);
        int o4 = orient(cx, cz, dx, dz, bx, bz);

        if (o1 != o2 && o3 != o4) return true;
        // colinear cases
        if (o1 == 0 && onSeg(ax, az, bx, bz, cx, cz)) return true;
        if (o2 == 0 && onSeg(ax, az, bx, bz, dx, dz)) return true;
        if (o3 == 0 && onSeg(cx, cz, dx, dz, ax, az)) return true;
        return o4 == 0 && onSeg(cx, cz, dx, dz, bx, bz);
    }

    // orientation of (a->b) x (a->c); returns -1,0,1
    static int orient(int ax, int az, int bx, int bz, int cx, int cz) {
        long v = (long) (bx - ax) * (cz - az) - (long) (bz - az) * (cx - ax);
        if (v == 0) return 0;
        return v > 0 ? 1 : -1;
    }

    static boolean onSeg(int ax, int az, int bx, int bz, int px, int pz) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }

    static int[] computeLeadPoint(int[] p, String port, int dist, List<Rect2> avoids) {
        if (p == null || dist <= 0) return null;
        int[] d = dirFromPort(port);
        if (d == null) return null;
        int y = p[1];
        // shrink if target falls inside avoid
        for (int k = dist; k >= 1; k--) {
            int x = p[0] + d[0] * k;
            int z = p[2] + d[1] * k;
            if (!isBlocked(x, z, avoids)) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    // Returns [dx, dz] in local XZ for a port name. Diagonals return null (no lead).
    static int[] dirFromPort(String port) {
        if (port == null) return null;
        String p = AssemblyCompilerUtils.normalizePortKey(port);
        // allow prefixed directional ports like start_east / end_north / entrance_south
        String base = p;
        int idx = p.lastIndexOf('_');
        if (idx >= 0 && idx + 1 < p.length()) base = p.substring(idx + 1);
        return switch (base) {
            case "north", "back", "exit" -> new int[]{0, -1};
            case "south", "front", "entrance", "gate", "in" -> new int[]{0, 1};
            case "east", "right" -> new int[]{1, 0};
            case "west", "left" -> new int[]{-1, 0};
            default -> null;
        };
    }

}
