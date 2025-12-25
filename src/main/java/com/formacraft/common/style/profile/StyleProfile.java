package com.formacraft.common.style.profile;

import java.util.Set;

/**
 * StyleProfile: phenotype expression layer (v1).
 * Skeleton decides "where/what"; StyleProfile decides "how it looks".
 */
public interface StyleProfile {
    String id();

    StyleCategory category();

    BlockPalette palette();

    StyleRules rules();

    DetailPreferences details();

    /**
     * Resolve build strategy for a semantic role + tags.
     * v1: kept generic (role string) to avoid forcing a global NodeRole refactor.
     */
    BuildStrategy resolve(String role, Set<String> semanticTags);
}


