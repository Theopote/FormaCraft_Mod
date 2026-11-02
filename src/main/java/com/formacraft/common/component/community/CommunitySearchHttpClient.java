package com.formacraft.common.component.community;

import com.formacraft.common.component.ComponentPackageIO;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit blocking read-only search. Owns a credential-free client; caller closes it. */
public final class CommunitySearchHttpClient implements AutoCloseable {
    private final String authorityId;
    private final URI endpoint;
    private final URI apiRoot;
    private final HttpClient http;
    public CommunitySearchHttpClient(String authorityId, URI base) {
        if (authorityId == null || !authorityId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) throw new IllegalArgumentException("Invalid authority");
        Objects.requireNonNull(base);
        String prefix = getString(base);
        this.authorityId = authorityId; this.apiRoot = base.resolve(prefix + "v1/"); this.endpoint = apiRoot.resolve("search");
        // No default CookieHandler, Authenticator, proxy credentials, or redirect destination.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
                }).build();
    }

    private static @NotNull String getString(URI base) {
        boolean loopback = Set.of("127.0.0.1", "[::1]", "::1").contains(base.getHost() == null ? "" : base.getHost());
        String path = base.getPath();
        if (base.isOpaque() || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && loopback)
                || base.getPort() == 0 || base.getPort() > 65535 || !base.normalize().equals(base)
                || path == null || path.startsWith("//") || !path.matches("[/a-zA-Z0-9_-]*") || base.getRawPath().contains("%"))
            throw new IllegalArgumentException("Invalid community base URI");
        String prefix = path.endsWith("/") ? path : path + "/";
        return prefix;
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean(), used = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
        public void cancel() { cancelled.set(true); var active = future.get(); if (active != null) active.cancel(true); }
        private void check() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CommunityPackages.Failure("cancelled");
        }
    }
    public CommunitySearchResponse.Decoded search(byte[] requestBytes, Cancellation cancellation) throws IOException {
        claim(cancellation);
        if (requestBytes == null || requestBytes.length > CommunitySearchContract.MAX_REQUEST_BYTES)
            throw new CommunityPackages.Failure(requestBytes == null ? "invalid_request" : "request_too_large");
        byte[] owned = requestBytes.clone(); var request = CommunitySearchContract.decode(owned);
        CommunitySearchContract.requireOfflineCapabilities(request);
        var outgoing = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Content-Type", "application/json; charset=utf-8")
                .header("Accept-Encoding", "identity").POST(HttpRequest.BodyPublishers.ofByteArray(owned)).build();
        var response = exchange(outgoing, cancellation, CommunitySearchResponse.MAX_BYTES, -1, null);
        if (response.statusCode() != 200) throw remoteFailure(response.body(), request.requestId());
        var decoded = CommunitySearchResponse.decode(response.body(), request, authorityId); cancellation.check(); return decoded;
    }

    public CommunityReleaseDetails details(CommunityPackages.Descriptor selected, Cancellation cancellation) throws IOException {
        claim(cancellation); requireAuthority(selected);
        var details = fetchDetails(selected, cancellation); cancellation.check(); return details;
    }

    public record Fetched(CommunityReleaseDetails details, CommunityPackages.Download download) {}

    public Fetched download(CommunityPackages.Descriptor selected, ComponentPackageIO.Environment environment, Cancellation cancellation) throws IOException {
        claim(cancellation); requireAuthority(selected);
        if (environment == null) throw new CommunityPackages.Failure("environment_required");
        var details = fetchDetails(selected, cancellation);
        if (!details.available()) throw new CommunityPackages.Failure("release_unavailable");
        cancellation.check(); String requestId = UUID.randomUUID().toString(); var ref = selected.releaseRef();
        var uri = apiRoot.resolve("package-blobs/" + ref.packageSha256() + "?releaseId=" + ref.releaseId());
        var request = get(uri, requestId);
        var response = exchange(request, cancellation, selected.byteLength(), selected.byteLength(), "\"" + ref.packageSha256() + "\"");
        if (response.statusCode() != 200) throw remoteFailure(response.body(), requestId);
        cancellation.check();
        var download = CommunityPackages.receive(new ByteArrayInputStream(response.body()), selected, environment);
        details.verify(download); cancellation.check(); return new Fetched(details, download);
    }

    private CommunityReleaseDetails fetchDetails(CommunityPackages.Descriptor selected, Cancellation cancellation) throws IOException {
        String requestId = UUID.randomUUID().toString();
        var request = get(apiRoot.resolve("releases/" + selected.releaseRef().releaseId()), requestId);
        var response = exchange(request, cancellation, CommunityReleaseDetails.MAX_BYTES, -1, null);
        if (response.statusCode() != 200) throw remoteFailure(response.body(), requestId);
        var details = CommunityReleaseDetails.decode(response.body(), selected); cancellation.check(); return details;
    }
    private static HttpRequest get(URI uri, String requestId) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("X-Formacraft-Request-Id", requestId).GET().build();
    }
    private void requireAuthority(CommunityPackages.Descriptor selected) throws IOException {
        if (selected == null || !authorityId.equals(selected.releaseRef().authorityId())) throw new CommunityPackages.Failure("authority_mismatch");
    }
    private static void claim(Cancellation cancellation) throws IOException {
        Objects.requireNonNull(cancellation);
        if (!cancellation.used.compareAndSet(false, true)) throw new IllegalArgumentException("Cancellation token already used");
        cancellation.check();
    }

    private HttpResponse<byte[]> exchange(HttpRequest outgoing, Cancellation cancellation, int limit, int expectedLength, String etag) throws IOException {
        cancellation.check();
        var future = http.sendAsync(outgoing, info -> subscriber(info, limit, expectedLength, etag)); cancellation.future.set(future);
        try {
            cancellation.check(); var response = future.get(30, TimeUnit.SECONDS); cancellation.check();
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CommunityPackages.Failure("cancelled", interrupted);
        } catch (TimeoutException timeout) {
            throw new CommunityPackages.Failure("request_timeout", timeout);
        } catch (CancellationException cancelled) {
            cancellation.check(); throw new CommunityPackages.Failure("service_unavailable", cancelled);
        } catch (ExecutionException failed) {
            cancellation.check(); Throwable cause = failed;
            for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
                if (cause instanceof CommunityPackages.Failure failure) throw failure;
                if (cause instanceof HttpTimeoutException) throw new CommunityPackages.Failure("request_timeout", cause);
            }
            throw new CommunityPackages.Failure("service_unavailable", failed);
        } finally {
            cancellation.future.compareAndSet(future, null); if (!future.isDone()) future.cancel(true);
        }
    }
    private static LimitedBody subscriber(HttpResponse.ResponseInfo info, int limit, int expectedLength, String etag) {
        var body = new LimitedBody(info.statusCode() == 200 ? limit : CommunitySearchResponse.MAX_BYTES);
        int status = info.statusCode();
        if (status >= 300 && status < 400) body.fail("redirect_rejected");
        else if (status == 429) body.fail("rate_limited");
        else if (status >= 500) body.fail("service_unavailable");
        else if (status >= 200 && status < 300 && status != 200) body.fail("invalid_response");
        else {
            String encoding = info.headers().firstValue("Content-Encoding").orElse("identity");
            String contentType = info.headers().firstValue("Content-Type").orElse("");
            if (!encoding.equalsIgnoreCase("identity")) body.fail("unsupported_encoding");
            else if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) body.fail("invalid_response");
            else {
                try {
                    body.declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (body.declared > body.limit) body.fail("response_too_large");
                    else if (body.declared < -1) body.fail("invalid_response");
                    else if (status == 200 && expectedLength >= 0 && body.declared >= 0 && body.declared != expectedLength) body.fail("object_changed");
                    if (status == 200) body.expectedLength = expectedLength;
                    if (status == 200 && etag != null && !etag.equals(info.headers().firstValue("ETag").orElse(null))) body.fail("object_changed");
                } catch (NumberFormatException invalid) { body.fail("invalid_response"); }
            }
        }
        return body;
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        long declared = -1;
        final int limit;
        int expectedLength = -1;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; if (result.isDone()) subscription.cancel(); else subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                int count = buffer.remaining();
                if (count > limit - bytes.size()) { fail("response_too_large"); return; }
                byte[] part = new byte[count]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() {
            if (expectedLength >= 0 && expectedLength != bytes.size()) fail("download_incomplete");
            else if (declared >= 0 && declared != bytes.size()) fail("invalid_response"); else result.complete(bytes.toByteArray());
        }
        void fail(String code) { result.completeExceptionally(new CommunityPackages.Failure(code)); if (subscription != null) subscription.cancel(); }
    }
    private static IOException remoteFailure(byte[] bytes, String requestId) {
        try {
            var error = CommunityJson.read(bytes, CommunitySearchResponse.MAX_BYTES);
            CommunityJson.keys(error, "requestId", "code", "message", "retryable", "retryAfterSeconds");
            if (!requestId.equals(CommunityJson.text(error.get("requestId"), 128))) return new CommunityPackages.Failure("invalid_response");
            String code = CommunityJson.text(error.get("code"), 64);
            CommunityJson.text(error.get("message"), 512); CommunityJson.bool(error.get("retryable"));
            if (error.has("retryAfterSeconds")) CommunityJson.integer(error.get("retryAfterSeconds"), 0, 3600);
            if (Set.of("invalid_request", "request_too_large", "unsupported_contract", "unsupported_capability", "unsupported_constraint",
                    "cursor_expired", "cursor_mismatch", "summary_too_large", "budget_exceeded", "release_unavailable").contains(code))
                return new CommunityPackages.Failure(code);
        } catch (IOException | RuntimeException invalid) { return new CommunityPackages.Failure("invalid_response"); }
        return new CommunityPackages.Failure("service_rejected");
    }
    @Override public void close() { http.close(); }
}
