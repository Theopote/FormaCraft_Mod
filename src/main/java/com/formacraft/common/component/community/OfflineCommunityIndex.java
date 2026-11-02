package com.formacraft.common.component.community;

import com.formacraft.common.component.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Bounded public-only fixture index; deliberately not an HTTP/wire-contract implementation. */
public final class OfflineCommunityIndex {
    public static final int MAX_RELEASES = 256, MAX_BYTES = 32 * 1024 * 1024, MAX_CURSORS = 128;
    public record Query(String exactTypeKey, Set<String> hardTags) {
        public Query {
            if (exactTypeKey != null && (exactTypeKey.isBlank() || exactTypeKey.length() > 128))
                throw new IllegalArgumentException("Invalid type key");
            hardTags = hardTags == null ? Set.of() : Set.copyOf(hardTags);
            if (hardTags.size() > 64 || hardTags.stream().anyMatch(t -> t.isBlank() || t.codePointCount(0, t.length()) > 128))
                throw new IllegalArgumentException("Invalid tags");
        }
    }
    public record SourceClaim(String namespace, String componentId, String version, String author) {}
    public record Candidate(CommunityPackages.Descriptor descriptor, String sourceAssetKey,
                            String title, String typeKey, String license,
                            ComponentPackageIO.TrainingPermission trainingPermission,
                            String actualFit, SourceClaim sourceClaim) {}
    public record Group(String configurationFingerprint, Candidate preferredCandidate, int matchingSourceCount) {}
    public record Page(List<Group> items, String nextCursor, String snapshotId, Instant expiresAt) {
        public Page { items = List.copyOf(items); }
    }
    private record Entry(CommunityPackages.Descriptor descriptor, byte[] bytes,
                         ComponentPackageIO.Verified inspected) {}
    private record Cursor(Query query, int pageSize, String contextKey, List<List<String>> groups, int offset, Instant expires, String snapshotId) {}
    private final String authorityId;
    private final Clock clock;
    private final Map<String, Entry> entries = new TreeMap<>();
    private final Set<String> unavailable = new HashSet<>();
    private final Map<String, Cursor> cursors = new HashMap<>();
    private int storedBytes;

    public OfflineCommunityIndex(String authorityId, Clock clock) {
        if (authorityId == null || !authorityId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid authority");
        this.authorityId = authorityId; this.clock = Objects.requireNonNull(clock);
    }

    /** Immutable release identities cannot be overwritten, including after withdrawal. */
    public synchronized void add(CommunityPackages.Descriptor descriptor, byte[] bytes) throws IOException {
        if (!authorityId.equals(descriptor.releaseRef().authorityId())) throw new CommunityPackages.Failure("authority_mismatch");
        String id = descriptor.releaseRef().releaseId();
        if (entries.containsKey(id)) throw new CommunityPackages.Failure("release_exists");
        if (entries.size() >= MAX_RELEASES || bytes == null || bytes.length > MAX_BYTES - storedBytes)
            throw new CommunityPackages.Failure("budget_exceeded");
        byte[] owned = bytes.clone();
        var inspected = CommunityPackages.inspect(owned, descriptor);
        entries.put(id, new Entry(descriptor, owned, inspected)); storedBytes += owned.length;
    }

    public synchronized void setAvailable(String releaseId, boolean available) throws IOException {
        requireEntry(releaseId);
        if (available) unavailable.remove(releaseId); else unavailable.add(releaseId);
    }

    public synchronized CommunityPackages.Download download(CommunityPackages.ReleaseRef selected,
                                                            ComponentPackageIO.Environment environment) throws IOException {
        if (selected == null || !authorityId.equals(selected.authorityId()))
            throw new CommunityPackages.Failure("authority_mismatch");
        Entry entry = requireEntry(selected.releaseId());
        if (unavailable.contains(selected.releaseId())) throw new CommunityPackages.Failure("release_unavailable");
        if (!entry.descriptor.releaseRef().equals(selected)) throw new CommunityPackages.Failure("object_changed");
        return CommunityPackages.receive(new ByteArrayInputStream(entry.bytes), entry.descriptor, environment);
    }

    public synchronized byte[] releaseDetails(CommunityPackages.ReleaseRef selected) throws IOException {
        Entry entry = selectedEntry(selected);
        return CommunityReleaseDetails.create(entry.descriptor, entry.inspected, !unavailable.contains(selected.releaseId())).toBytes();
    }

    public synchronized byte[] packageBytes(CommunityPackages.ReleaseRef selected) throws IOException {
        Entry entry = selectedEntry(selected);
        if (unavailable.contains(selected.releaseId())) throw new CommunityPackages.Failure("release_unavailable");
        return entry.bytes.clone();
    }

    private Entry selectedEntry(CommunityPackages.ReleaseRef selected) throws IOException {
        if (selected == null || !authorityId.equals(selected.authorityId())) throw new CommunityPackages.Failure("authority_mismatch");
        Entry entry = requireEntry(selected.releaseId());
        if (!entry.descriptor.releaseRef().equals(selected)) throw new CommunityPackages.Failure("object_changed");
        return entry;
    }

    public synchronized Page search(Query query, int pageSize, String token) throws IOException {
        return search(query, pageSize, token, "internal");
    }

    synchronized Page search(Query query, int pageSize, String token, String contextKey) throws IOException {
        return search(query, pageSize, token, contextKey, groups -> true);
    }

    synchronized Page search(Query query, int pageSize, String token, String contextKey,
                             Predicate<List<Group>> fits) throws IOException {
        Objects.requireNonNull(query);
        if (pageSize < 1 || pageSize > 20) throw new CommunityPackages.Failure("invalid_page_size");
        Instant now = clock.instant();
        Cursor cursor;
        if (token != null) {
            cursor = cursors.get(token);
            if (cursor == null || !now.isBefore(cursor.expires)) {
                cursors.remove(token); throw new CommunityPackages.Failure("cursor_expired");
            }
            if (!query.equals(cursor.query) || pageSize != cursor.pageSize || !contextKey.equals(cursor.contextKey))
                throw new CommunityPackages.Failure("cursor_mismatch");
        } else {
            List<List<String>> groups = new ArrayList<>();
            for (var item : entries.entrySet()) {
                if (unavailable.contains(item.getKey()) || !matches(query, item.getValue())) continue;
                List<String> group = groups.stream().filter(g -> sameConfiguration(entries.get(g.getFirst()), item.getValue()))
                        .findFirst().orElse(null);
                if (group == null) { group = new ArrayList<>(); groups.add(group); }
                group.add(item.getKey());
            }
            cursor = new Cursor(query, pageSize, contextKey, groups.stream().map(List::copyOf).toList(), 0, now.plusSeconds(300), UUID.randomUUID().toString());
        }
        List<Group> result = new ArrayList<>(); int offset = cursor.offset;
        while (offset < cursor.groups.size() && result.size() < pageSize) {
            List<String> visible = cursor.groups.get(offset++).stream().filter(id -> !unavailable.contains(id)).toList();
            if (visible.isEmpty()) continue;
            Entry entry = entries.get(visible.getFirst()); var def = entry.inspected.component();
            var manifest = entry.inspected.manifest();
            var candidate = new Candidate(entry.descriptor, entry.inspected.assetKey(), def.name,
                    ComponentTypes.effective(def.type, def.category).key(), manifest.license(), manifest.trainingPermission(), "unknown",
                    new SourceClaim(manifest.namespace(), def.id, manifest.version(), manifest.author()));
            result.add(new Group(entry.descriptor.fingerprints().configuration(), candidate, visible.size()));
            if (!fits.test(List.copyOf(result))) {
                result.removeLast(); offset--;
                if (result.isEmpty()) throw new CommunityPackages.Failure("summary_too_large");
                break;
            }
        }
        String next = null;
        if (offset < cursor.groups.size()) {
            cursors.values().removeIf(c -> !now.isBefore(c.expires));
            if (cursors.size() >= MAX_CURSORS) throw new CommunityPackages.Failure("budget_exceeded");
            next = UUID.randomUUID().toString();
            cursors.put(next, new Cursor(query, pageSize, contextKey, cursor.groups, offset, cursor.expires, cursor.snapshotId));
        }
        return new Page(result, next, cursor.snapshotId, cursor.expires);
    }

    private Entry requireEntry(String id) throws IOException {
        Entry entry = entries.get(id);
        if (entry == null) throw new CommunityPackages.Failure("release_unavailable");
        return entry;
    }
    private static boolean sameConfiguration(Entry a, Entry b) {
        return a.descriptor.fingerprints().configuration().equals(b.descriptor.fingerprints().configuration())
                && ComponentContentIdentity.compare(a.inspected.component(), b.inspected.component()) == ComponentContentIdentity.Match.EXACT_CONTENT;
    }
    private static boolean matches(Query query, Entry entry) {
        var def = entry.inspected.component();
        return (query.exactTypeKey == null || query.exactTypeKey.equals(ComponentTypes.effective(def.type, def.category).key()))
                && (query.hardTags.isEmpty() || def.tags != null && def.tags.containsAll(query.hardTags));
    }
}
