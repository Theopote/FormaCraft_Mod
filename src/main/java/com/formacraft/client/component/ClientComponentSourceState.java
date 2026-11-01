package com.formacraft.client.component;

import com.formacraft.common.component.ComponentSourceView;
import com.formacraft.common.json.JsonUtil;
import java.util.UUID;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One pending, correlated request; no metadata is reused across disconnects or selection changes. */
public final class ClientComponentSourceState {
    public static final ClientComponentSourceState INSTANCE = new ClientComponentSourceState(System::nanoTime);
    private final LongSupplier clock;
    private UUID token;
    private String assetId, error;
    private int index;
    private long started;
    private boolean loading;
    private ComponentSourceView.Page page;
    public ClientComponentSourceState(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public UUID begin(String id, int pageIndex) {
        clear(); assetId = id; index = pageIndex; token = UUID.randomUUID(); started = clock.getAsLong(); loading = true;
        return token;
    }
    public void unavailable(String id, String message) { clear(); assetId = id; error = message; }
    public void accept(UUID request, String json) {
        expire();
        if (!loading || !Objects.equals(token, request)) return;
        loading = false;
        try {
            var received = JsonUtil.fromJson(json, ComponentSourceView.Page.class);
            if (received == null || !Objects.equals(assetId, received.assetId()) || index != received.index()
                    || received.total() < 0 || received.total() > 129
                    || received.error() == null && (received.total() <= index || index < 0 || received.trainingPermission() == null
                    || received.license() == null || received.author() == null || received.name() == null))
                throw new IllegalArgumentException("Mismatched source page");
            if (received.error() != null) error = received.error(); else page = received;
        } catch (RuntimeException invalid) { error = "来源数据无效，请重试"; }
    }
    public void clear() { token = null; assetId = null; error = null; page = null; loading = false; }
    private void expire() {
        if (loading && clock.getAsLong() - started >= 10_000_000_000L) {
            loading = false; token = null; error = "来源请求超时，请重试";
        }
    }
    public String assetId() { return assetId; }
    public boolean loading() { expire(); return loading; }
    public String error() { expire(); return error; }
    public ComponentSourceView.Page page() { expire(); return page; }
}
