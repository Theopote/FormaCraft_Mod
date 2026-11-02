package com.formacraft.common.component.community;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Unverified contiguous blocks, separate from the installable raw-object/receipt cache. */
public final class CommunityResumeStore {
    private CommunityResumeStore() {}
    public static final int CHUNK_BYTES = 256 * 1024, MAX_TRANSFERS = 16, MAX_FILES = 130;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String SCHEMA = "formacraft.community.resume.v1", IDENTITY = "identity.json";
    private static final Set<Path> ACTIVE = new HashSet<>();
    public record Progress(int completedBytes, int totalBytes) {}

    public static Progress inspect(Path root, CommunityPackages.Descriptor descriptor) throws IOException {
        try (var state = open(root, descriptor, false, true)) { return new Progress(state.position, descriptor.byteLength()); }
        catch (NoSuchFileException missing) { return new Progress(0, descriptor.byteLength()); }
    }
    public static void discard(Path root, CommunityPackages.Descriptor descriptor) throws IOException {
        try (var state = open(root, descriptor, false, false)) { state.discard(); }
        catch (NoSuchFileException missing) { /* Already absent. */ }
    }
    static State open(Path root, CommunityPackages.Descriptor descriptor) throws IOException { return open(root, descriptor, true, true); }
    private static synchronized State open(Path root, CommunityPackages.Descriptor descriptor, boolean create, boolean validate) throws IOException {
        Objects.requireNonNull(root); Objects.requireNonNull(descriptor);
        Path absolute = root.toAbsolutePath().normalize();
        String id = "resume_" + CommunityDownloadCache.receiptId(descriptor).substring("receipt_".length());
        Path path = absolute.resolve(id);
        if (ACTIVE.contains(path)) throw new CommunityPackages.Failure("download_busy");
        ACTIVE.add(path);
        try {
            if (create) Files.createDirectories(absolute);
            directory(absolute);
            if (create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                long[] usage = usage(absolute);
                if (usage[0] >= MAX_TRANSFERS) throw new CommunityPackages.Failure("resume_capacity");
                Files.createDirectory(path);
            }
            directory(path);
            var state = new State(absolute, path, descriptor);
            if (validate) {
                if (!Files.exists(path.resolve(IDENTITY), LinkOption.NOFOLLOW_LINKS)) {
                    // Missing metadata is acceptable only for a new empty transfer.
                    try (var entries = Files.newDirectoryStream(path)) { if (entries.iterator().hasNext()) throw invalid(); }
                    if (!create) throw invalid();
                    var metadata = new JsonObject(); metadata.addProperty("schema", SCHEMA); metadata.addProperty("chunkBytes", CHUNK_BYTES);
                    metadata.add("descriptor", CommunityReleaseDetails.descriptorJson(descriptor));
                    byte[] bytes = CommunityReleaseDetails.encode(metadata, 4096);
                    capacity(absolute, bytes.length); write(path, IDENTITY, bytes);
                }
                var metadata = CommunityJson.read(read(path.resolve(IDENTITY), 4096), 4096);
                CommunityReleaseDetails.exact(metadata, "schema", "chunkBytes", "descriptor");
                if (!SCHEMA.equals(text(metadata.get("schema"), 128)) || integer(metadata.get("chunkBytes"), 1, CHUNK_BYTES) != CHUNK_BYTES
                        || !descriptor.equals(CommunityReleaseDetails.descriptor(object(metadata.get("descriptor"))))) throw invalid();
                state.load();
            }
            return state;
        } catch (IOException | RuntimeException failed) {
            ACTIVE.remove(path);
            if (failed instanceof NoSuchFileException missing) throw missing;
            if (failed instanceof CommunityPackages.Failure failure && Set.of("resume_capacity", "invalid_checkpoint").contains(failure.code())) throw failure;
            throw new CommunityPackages.Failure("invalid_checkpoint", failed);
        }
    }
    static final class State implements AutoCloseable {
        private final Path root, path;
        private final CommunityPackages.Descriptor descriptor;
        private byte[] prefix = new byte[0];
        private int position;
        private boolean closed, discarded;
        private State(Path root, Path path, CommunityPackages.Descriptor descriptor) { this.root = root; this.path = path; this.descriptor = descriptor; }
        int position() { return position; }
        byte[] prefix() { return prefix.clone(); }
        private void load() throws IOException {
            var chunks = new TreeMap<Integer, Path>(); int count = 0;
            try (var files = Files.newDirectoryStream(path)) {
                for (var file : files) {
                    if (++count > MAX_FILES) throw invalid();
                    String name = file.getFileName().toString(); checkFile(file);
                    if (name.equals(IDENTITY) || pending(name)) continue;
                    if (!name.matches("[0-9]{5}_[0-9a-f]{64}\\.part")) throw invalid();
                    int index = Integer.parseInt(name.substring(0, 5));
                    if (chunks.put(index, file) != null) throw invalid();
                }
            }
            try (var bytes = new ByteArrayOutputStream()) {
                int index = 0;
                for (var entry : chunks.entrySet()) {
                    if (entry.getKey() != index++ || position >= descriptor.byteLength()) throw invalid();
                    byte[] chunk = read(entry.getValue(), CHUNK_BYTES);
                    int expected = Math.min(CHUNK_BYTES, descriptor.byteLength() - position);
                    String digest = entry.getValue().getFileName().toString().substring(6, 70);
                    if (chunk.length != expected || !CommunityPackages.sha256(chunk).equals(digest)) throw invalid();
                    bytes.writeBytes(chunk); position += chunk.length;
                }
                prefix = bytes.toByteArray();
            }
        }
        void append(int start, byte[] bytes) throws IOException {
            synchronized (CommunityResumeStore.class) {
                if (closed || discarded || position != start || start % CHUNK_BYTES != 0
                        || bytes.length != Math.min(CHUNK_BYTES, descriptor.byteLength() - start) || bytes.length == 0) throw invalid();
                capacity(root, bytes.length);
                int count = 0; try (var files = Files.newDirectoryStream(path)) { for (var ignored : files) count++; }
                if (count >= MAX_FILES) throw new CommunityPackages.Failure("resume_capacity");
                String name = String.format(Locale.ROOT, "%05d_%s.part", start / CHUNK_BYTES, CommunityPackages.sha256(bytes));
                if (Files.exists(path.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw invalid();
                write(path, name, bytes); position += bytes.length;
            }
        }
        void discard() throws IOException {
            synchronized (CommunityResumeStore.class) {
                if (closed || discarded) return;
                directory(path); var files = new ArrayList<Path>();
                try (var entries = Files.newDirectoryStream(path)) {
                    for (var file : entries) {
                        if (files.size() >= MAX_FILES) throw invalid(); checkFile(file);
                        String name = file.getFileName().toString();
                        if (!name.equals(IDENTITY) && !pending(name) && !name.matches("[0-9]{5}_[0-9a-f]{64}\\.part")) throw invalid();
                        files.add(file);
                    }
                }
                for (var file : files) Files.delete(file);
                Files.delete(path); discarded = true;
            }
        }
        @Override public void close() { synchronized (CommunityResumeStore.class) { if (!closed) { closed = true; ACTIVE.remove(path); } } }
    }
    private static void directory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(path.toString());
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw invalid();
    }
    private static void checkFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw invalid();
    }
    private static byte[] read(Path path, int max) throws IOException {
        checkFile(path);
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(max + 1); if (bytes.length > max) throw invalid(); return bytes;
        }
    }
    private static boolean pending(String name) { return name.matches("\\.pending-[0-9a-f-]{36}"); }
    private static void write(Path directory, String name, byte[] bytes) throws IOException {
        Path temporary = directory.resolve(".pending-" + UUID.randomUUID());
        try { Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW); Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static long[] usage(Path root) throws IOException {
        long transfers = 0, bytes = 0;
        try (var directories = Files.newDirectoryStream(root)) {
            for (var transfer : directories) {
                if (++transfers > MAX_TRANSFERS) throw new CommunityPackages.Failure("resume_capacity");
                if (!transfer.getFileName().toString().matches("resume_[0-9a-f]{64}")) throw invalid();
                directory(transfer); int count = 0;
                try (var files = Files.newDirectoryStream(transfer)) {
                    for (var file : files) {
                        if (++count > MAX_FILES) throw invalid(); checkFile(file);
                        String name = file.getFileName().toString();
                        if (!name.equals(IDENTITY) && !pending(name) && !name.matches("[0-9]{5}_[0-9a-f]{64}\\.part")) throw invalid();
                        long size = Files.size(file);
                        if (size > (name.equals(IDENTITY) ? 4096 : CHUNK_BYTES)) throw invalid();
                        bytes += size; if (bytes > MAX_BYTES) throw new CommunityPackages.Failure("resume_capacity");
                    }
                }
            }
        }
        return new long[]{transfers, bytes};
    }
    private static void capacity(Path root, int additional) throws IOException {
        if (additional > MAX_BYTES - usage(root)[1]) throw new CommunityPackages.Failure("resume_capacity");
    }
    private static IOException invalid() { return new CommunityPackages.Failure("invalid_checkpoint"); }
}
