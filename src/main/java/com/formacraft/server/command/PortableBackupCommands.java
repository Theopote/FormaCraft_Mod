package com.formacraft.server.command;

import com.formacraft.common.component.PortableAssetBackup;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static net.minecraft.server.command.CommandManager.*;

final class PortableBackupCommands {
    private PortableBackupCommands() {}
    private static final Map<MinecraftServer,Service> SERVICES=new IdentityHashMap<>();
    private static boolean lifecycle;
    private static final class Service {
        final Set<UUID> busy=new HashSet<>();
        final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),job -> {
            var thread=new Thread(job,"formacraft-portable-backup"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @FunctionalInterface private interface Work { String run(Path world,UUID owner) throws IOException; }
    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        synchronized(SERVICES) { if(!lifecycle) {
            lifecycle=true; ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                Service removed; synchronized(SERVICES) { removed=SERVICES.remove(server); }
                if(removed!=null) removed.worker.shutdownNow();
            });
        } }
        dispatcher.register(literal("forma_backup")
                .then(literal("export_recipe").then(argument("id",StringArgumentType.word()).executes(ctx -> export(ctx.getSource(),StringArgumentType.getString(ctx,"id"),true))))
                .then(literal("export_lock").then(argument("id",StringArgumentType.word()).executes(ctx -> export(ctx.getSource(),StringArgumentType.getString(ctx,"id"),false))))
                .then(literal("inspect").then(argument("file",StringArgumentType.word()).executes(ctx -> inspect(ctx.getSource(),StringArgumentType.getString(ctx,"file")))))
                .then(literal("import").then(argument("file",StringArgumentType.word()).then(argument("sha256",StringArgumentType.word())
                        .executes(ctx -> restore(ctx.getSource(),StringArgumentType.getString(ctx,"file"),StringArgumentType.getString(ctx,"sha256")))))));
    }
    private static int run(ServerCommandSource source,Work work) {
        try {
            var player=source.getPlayerOrThrow(); var server=source.getServer(); UUID owner=player.getUuid(); var world=server.getSavePath(WorldSavePath.ROOT);
            Service service; synchronized(SERVICES) { service=SERVICES.computeIfAbsent(server,ignored -> new Service()); }
            synchronized(service) { if(!service.busy.add(owner)) { source.sendError(Text.literal("已有备份操作正在进行")); return 0; } }
            try { service.worker.execute(() -> {
                String result;
                try { result=work.run(world,owner); }
                catch(Exception invalid) {
                    com.formacraft.FormacraftMod.LOGGER.warn("Portable backup operation failed owner={}",owner,invalid);
                    result="备份操作失败：请检查文件、依赖或目标冲突，详见服务器日志";
                } finally { synchronized(service) { service.busy.remove(owner); } }
                String message=result;
                try { server.execute(() -> { if(server.getPlayerManager().getPlayer(owner)==player) player.sendMessage(Text.literal(message),false); }); }
                catch(RuntimeException stopped) { /* A completed private artifact may remain; never message a new connection. */ }
            }); } catch(RejectedExecutionException full) {
                synchronized(service) { service.busy.remove(owner); } source.sendError(Text.literal("备份队列已满，请稍后重试")); return 0;
            }
            source.sendFeedback(() -> Text.literal("备份操作已排队，不会修改世界"),false); return 1;
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException noPlayer) { source.sendError(Text.literal("请由玩家操作本人私人备份")); return 0; }
    }
    private static int export(ServerCommandSource source,String id,boolean recipe) {
        return run(source,(world,owner) -> {
            Path folder=PortableAssetBackup.exchange(world,owner,"outbox"); PortableAssetBackup.outboxQuota(folder);
            String name="backup_"+UUID.randomUUID().toString().replace("-","")+".fcbackup"; Path file=folder.resolve(name); boolean published=false,accepted=false;
            try {
                var preview=recipe ? PortableAssetBackup.exportRecipe(owner,id,file) : PortableAssetBackup.exportLock(world,owner,id,file);
                published=true;
                PortableAssetBackup.outboxQuota(folder); accepted=true;
                return "已导出 "+name+"；SHA-256 "+preview.sha256()+"；位于存档 formacraft/backups/players/"+owner+"/outbox";
            } finally { if(published && !accepted) Files.deleteIfExists(file); }
        });
    }
    private static Path inbox(Path world,UUID owner,String name) throws IOException {
        if(!PortableAssetBackup.fileName(name)) throw new IOException("备份文件名无效"); return PortableAssetBackup.exchange(world,owner,"inbox").resolve(name);
    }
    private static int inspect(ServerCommandSource source,String name) {
        return run(source,(world,owner) -> {
            var preview=PortableAssetBackup.inspect(inbox(world,owner,name));
            return "备份校验通过："+preview.kind()+" / "+preview.label()+"；原归属 "+preview.originalOwner()+"；文件 "+preview.files()+"；展开 "+preview.expandedBytes()+" 字节。确认导入本人私人库：/forma_backup import "+name+" "+preview.sha256();
        });
    }
    private static int restore(ServerCommandSource source,String name,String sha) {
        return run(source,(world,owner) -> {
            var result=PortableAssetBackup.importArchive(inbox(world,owner,name),sha,world,owner);
            return "备份已"+(result.reused() ? "复用" : "导入")+"："+result.kind()+" / "+result.id()+"；仅写本人私人数据，请刷新构件库或使用 /forma_assets list";
        });
    }
}
