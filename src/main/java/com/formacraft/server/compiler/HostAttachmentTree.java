package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walk authored {@code host_id} trees (cycle-guarded) for geometry repairs that must
 * move an entire attachment subtree with a root mass/tower — without using shared slot.
 * Descendants are tracked by component index so attachments without {@code component_id}
 * still move when they declare {@code host_id}.
 */
final class HostAttachmentTree {
    private HostAttachmentTree() {}

    /**
     * Indices of components reachable as descendants of {@code rootId} via host_id edges.
     * Does not include the root mass/tower itself. Nodes without component_id are leaves
     * for further hosting but are still returned for a single shift.
     */
    static BitSet descendantIndices(String rootId, List<Component> components) {
        BitSet out = new BitSet();
        if (rootId == null || rootId.isBlank() || components == null) {
            return out;
        }
        Map<String, List<Integer>> children = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (c == null || c.params() == null) continue;
            String host = ComponentPlanTypes.getParamString(c.params(), "host_id", "hostId");
            if (host == null || host.isBlank()) continue;
            children.computeIfAbsent(host, ignored -> new ArrayList<>()).add(i);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seenHostKeys = new HashSet<>();
        queue.add(rootId);
        seenHostKeys.add(rootId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            List<Integer> kids = children.get(cur);
            if (kids == null) continue;
            for (int idx : kids) {
                if (out.get(idx)) {
                    continue; // already shifted once
                }
                out.set(idx);
                Component child = components.get(idx);
                if (child == null || child.params() == null) continue;
                String childId = ComponentPlanTypes.getParamString(
                        child.params(), "component_id", "componentId");
                if (childId != null && !childId.isBlank() && seenHostKeys.add(childId)) {
                    queue.add(childId);
                }
            }
        }
        return out;
    }

    /** Convenience for tests: component_ids among descendants that have an id. */
    static Set<String> descendantIds(String rootId, List<Component> components) {
        Set<String> out = new HashSet<>();
        BitSet indices = descendantIndices(rootId, components);
        for (int i = indices.nextSetBit(0); i >= 0; i = indices.nextSetBit(i + 1)) {
            Component c = components.get(i);
            if (c == null || c.params() == null) continue;
            String id = ComponentPlanTypes.getParamString(c.params(), "component_id", "componentId");
            if (id != null && !id.isBlank()) out.add(id);
        }
        return out;
    }
}
