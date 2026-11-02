package com.formacraft.common.component.community;

import com.formacraft.common.component.ComponentContentIdentity;
import com.formacraft.common.component.ComponentPackageIO;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Offline release verification. No network, installation, or filesystem writes. */
public final class CommunityPackages {
    private CommunityPackages() {}

    public static final class Failure extends IOException {
        private final String code;
        public Failure(String code) { super(code); this.code = code; }
        public Failure(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String code() { return code; }
    }

    public record ReleaseRef(String authorityId, String resourceId, String releaseId,
                             String packageSha256, String payloadSha256, String manifestSha256) {
        public ReleaseRef {
            if (!id(authorityId) || !id(resourceId) || !id(releaseId)
                    || !digest(packageSha256) || !digest(payloadSha256) || !digest(manifestSha256))
                throw new IllegalArgumentException("Invalid release reference");
        }
    }

    public record Descriptor(ReleaseRef releaseRef, int byteLength, String packageSchema,
                             ComponentContentIdentity.Fingerprints fingerprints) {
        public Descriptor {
            Objects.requireNonNull(releaseRef);
            Objects.requireNonNull(fingerprints);
            if (byteLength < 1 || byteLength > ComponentPackageIO.MAX_PACKAGE_BYTES)
                throw new IllegalArgumentException("Invalid package length");
            if (!ComponentPackageIO.SCHEMA.equals(packageSchema))
                throw new IllegalArgumentException("Unsupported package schema");
            if (!fingerprint(fingerprints.structure(), "structure-v1")
                    || !fingerprint(fingerprints.configuration(), "configuration-v1"))
                throw new IllegalArgumentException("Unsupported fingerprints");
        }
    }

    /** Raw bytes are private defensive copies, distinct from Verified.toBytes(). */
    public static final class Download {
        private final Descriptor descriptor;
        private final byte[] original;
        private final ComponentPackageIO.Verified verified;
        private Download(Descriptor descriptor, byte[] original, ComponentPackageIO.Verified verified) {
            this.descriptor = descriptor; this.original = original.clone(); this.verified = verified;
        }
        public Descriptor descriptor() { return descriptor; }
        public byte[] originalBytes() { return original.clone(); }
        public ComponentPackageIO.Verified verified() { return verified; }
        public String snapshotKey() { return verified.sha256() + "_" + verified.manifestSha256(); }
    }

    /** Reads at most the declared length plus one byte; caller owns/ closes the stream. */
    public static Download receive(InputStream input, Descriptor expected,
                                   ComponentPackageIO.Environment environment) throws IOException {
        Objects.requireNonNull(input); Objects.requireNonNull(expected);
        if (environment == null) throw new Failure("environment_required");
        byte[] bytes = input.readNBytes(expected.byteLength() + 1);
        inspect(bytes, expected);
        ComponentPackageIO.Verified verified;
        try { verified = ComponentPackageIO.readBytes(bytes, environment); }
        catch (IOException invalid) { throw new Failure("environment_mismatch", invalid); }
        return new Download(expected, bytes, verified);
    }

    /** Integrity-only inspection used by fixtures. Never grants environment/install permission. */
    public static ComponentPackageIO.Verified inspect(byte[] bytes, Descriptor expected) throws IOException {
        Objects.requireNonNull(expected);
        if (bytes == null || bytes.length != expected.byteLength()) throw new Failure("download_incomplete");
        if (!sha256(bytes).equals(expected.releaseRef().packageSha256())) throw new Failure("package_hash_mismatch");
        ComponentPackageIO.Verified verified;
        try { verified = ComponentPackageIO.inspectBytes(bytes); }
        catch (IOException invalid) { throw new Failure("invalid_package", invalid); }
        if (!verified.sha256().equals(expected.releaseRef().payloadSha256())) throw new Failure("payload_hash_mismatch");
        if (!verified.manifestSha256().equals(expected.releaseRef().manifestSha256())) throw new Failure("manifest_hash_mismatch");
        ComponentContentIdentity.Fingerprints actual;
        try { actual = ComponentContentIdentity.fingerprints(verified.component()); }
        catch (RuntimeException invalid) { throw new Failure("invalid_package", invalid); }
        if (!actual.equals(expected.fingerprints())) throw new Failure("fingerprint_mismatch");
        return verified;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean id(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"); }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean fingerprint(String value, String version) {
        return value != null && value.startsWith(version + ":sha256:") && digest(value.substring(version.length() + 8));
    }
}
