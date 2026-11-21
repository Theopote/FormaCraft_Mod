package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walk authored {@code host_id} trees (cycle-guarded) for geometry repairs that must
 * move an entire attachment subtree with a root mass/tower — without using shared slot.
 */
final class HostAttachmentTree {
    private HostAttachmentTree() {}

    /**
     * All component_ids reachable as descendants of {@code rootId} via host_id edges.
     * Does not include {@code rootId} itself. Ambiguous duplicate ids are still listed
     * as children of their authored host; callers shift by component identity match.
     */
    static Set<String> descendantIds(String rootId, List<Component> components) {
        Set<String> out = new HashSet<>();
        if (rootId == null || rootId.isBlank() || components == null) {
            return out;
        }
        Map<String, List<String>> children = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            String id = ComponentPlanTypes.getParamString(c.params(), "component_id", "componentId");
            String host = ComponentPlanTypes.getParamString(c.params(), "host_id", "hostId");
            if (id == null || host == null) continue;
            children.computeIfAbsent(host, ignored -> new ArrayList<>()).add(id);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(rootId);
        seen.add(rootId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            List<String> kids = children.get(cur);
            if (kids == null) continue;
            for (String child : kids) {
                if (!seen.add(child)) {
                    continue; // cycle
                }
                out.add(child);
                queue.add(child);
            }
        }
        return out;
    }
}
