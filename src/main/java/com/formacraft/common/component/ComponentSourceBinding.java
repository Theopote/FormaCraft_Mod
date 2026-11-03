package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit source selection; a deduplicated local ID never replaces the selected package. */
public final class ComponentSourceBinding {
    private ComponentSourceBinding() {}
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    public static final class Selection {
        private final Path world;
        private final UUID owner;
        private final String assetId;
        private final ComponentPackageInstallations.ImportStatus status;
        private final CommunityDownloadCache.Cached receipt;
        private Selection(Path world, UUID owner, String assetId, ComponentPackageInstallations.ImportStatus status, CommunityDownloadCache.Cached receipt) {
            this.world = normalize(world); this.owner = owner; this.assetId = assetId; this.status = status; this.receipt = receipt;
        }
        public String assetId() { return assetId; }
        public ComponentPackageInstallations.ImportStatus status() { return status; }
        public CommunityDownloadCache.Cached receipt() { return receipt; }
        public ComponentPackageIO.Verified source() { return receipt.download().verified(); }
        public void validate(Path world, UUID viewer) throws IOException {
            if (!owner.equals(viewer) || !Objects.equals(this.world, normalize(world))) throw new IOException("所选来源不属于当前任务上下文");
            var entry = ComponentStorage.loadCatalog(world, viewer).components.stream().filter(e -> assetId.equals(e.id)).findFirst()
                    .orElseThrow(() -> new IOException("所选构件已不可见"));
            var visible = ComponentLibraryOperations.export(world, viewer, assetId, entry.packageRef, 0, entry.packageRef);
            if (ComponentContentIdentity.compare(visible.component(), source().component()) != ComponentContentIdentity.Match.EXACT_CONTENT)
                throw new IOException("所选构件别名已改变配置");
        }
        public Prepared prepare(Path world, UUID viewer, ComponentQuery query) throws IOException {
            validate(world, viewer);
            var base = source().runtimeComponent(assetId);
            var fitted = query == null ? base : ComponentModelApi.fitToQuery(base, query, null);
            if (fitted == null) throw new IOException("所选来源不满足目标适配，不能替换来源");
            return new Prepared(this, fitted);
        }
    }
    public static Selection install(Path cacheRoot, String receiptId, ComponentStorage.SaveContext context) throws IOException {
        if (context == null || context.playerId() == null) throw new IOException("显式来源需要私人玩家上下文");
        var receipt = CommunityDownloadCache.read(cacheRoot, receiptId, ComponentPackageIO.currentEnvironment());
        return install(receipt, context);
    }
    /** Read-only reconstruction: do not retain large source packages in idle task/session state. */
    public static Selection resolveInstalled(Path cacheRoot,String receiptId,String assetId,ComponentPackageInstallations.ImportStatus status,
                                            ComponentStorage.SaveContext context) throws IOException {
        if(context==null || context.playerId()==null || status==null || !ComponentIds.isSafeId(assetId)) throw new IOException("所选来源上下文无效");
        var receipt=CommunityDownloadCache.read(cacheRoot,receiptId,ComponentPackageIO.currentEnvironment());
        var selected=new Selection(context.worldDir(),context.playerId(),assetId,status,receipt);
        selected.validate(context.worldDir(),context.playerId()); return selected;
    }
    static Selection install(CommunityDownloadCache.Cached receipt, ComponentStorage.SaveContext context) throws IOException {
        if (context == null || context.playerId() == null) throw new IOException("显式来源需要私人玩家上下文");
        if (!CommunityDownloadCache.receiptId(receipt.details().descriptor()).equals(receipt.receiptId())) throw new IOException("所选收据身份不一致");
        receipt.details().verify(receipt.download());
        var source = CommunityPackages.receive(new java.io.ByteArrayInputStream(receipt.download().originalBytes()), receipt.details().descriptor(), ComponentPackageIO.currentEnvironment());
        var privateContext = new ComponentStorage.SaveContext(context.worldDir(), context.playerId(), false, false);
        var imported = ComponentPackageInstallations.importVerified(source.verified(), privateContext);
        return new Selection(context.worldDir(), context.playerId(), imported.assetId(), imported.status(), receipt);
    }
    public static final class Prepared {
        private final Selection selection;
        private final ComponentDefinition definition;
        private final String expected;
        private Prepared(Selection selection, ComponentDefinition definition) {
            this.selection = selection; this.definition = definition; this.expected = JsonUtil.toJson(definition);
        }
        public ComponentDefinition definition() { return definition; }
        private void check() throws IOException { if (!expected.equals(JsonUtil.toJson(definition))) throw new IOException("已绑定的适配定义被修改"); }
        public List<BlockPatch> compile(Path world, UUID viewer, Direction from, ComponentTransform transform, int x, int y, int z,
                                        boolean material, String style, long seed) throws IOException {
            selection.validate(world, viewer); check();
            try (var scope = open(List.of(this))) { return ComponentDefinitionCompiler.compile(definition, from, transform, x, y, z, material, style, seed); }
        }
    }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final Map<ComponentDefinition, Prepared> definitions = new IdentityHashMap<>();
        private boolean closed;
        private Scope(List<Prepared> prepared) throws IOException {
            if (prepared == null || prepared.size() > 256) throw new IOException("显式来源作用域超限");
            for (var item : prepared) { item.check(); if (definitions.put(item.definition, item) != null) throw new IOException("适配定义重复绑定"); }
            previous = ACTIVE.get(); ACTIVE.set(this);
        }
        public void close() {
            if (closed) return;
            if (ACTIVE.get() != this) throw new IllegalStateException("来源作用域关闭顺序错误");
            closed = true; if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
    public static Scope open(List<Prepared> prepared) throws IOException { return new Scope(prepared); }
    static Selection lookup(Path world, UUID viewer, ComponentDefinition definition) throws IOException {
        var scope = ACTIVE.get(); if (scope == null) return null;
        // An inner scope deliberately isolates the outer selection, including equal local IDs.
        var prepared = scope.definitions.get(definition); if (prepared == null) return null;
        prepared.check(); prepared.selection.validate(world, viewer); return prepared.selection;
    }
    static void assertSource(CommunityDownloadCache.Cached receipt, ComponentPackageIO.Verified source) throws IOException {
        if (!CommunityDownloadCache.receiptId(receipt.details().descriptor()).equals(receipt.receiptId())
                || !source.contentRef().equals(receipt.download().verified().contentRef()) || !source.manifestSha256().equals(receipt.download().verified().manifestSha256()))
            throw new IOException("固定收据与原包快照不一致");
        receipt.details().verify(receipt.download());
    }
    private static Path normalize(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
}
