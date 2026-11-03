package com.formacraft.client.ui.panel;

import com.formacraft.client.component.*;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentTypes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import java.io.IOException;
import java.util.*;

/** Embedded, clipped community browser. Networking stays in the tick-driven task client. */
final class CommunityLibraryPane {
    private final MinecraftClient client = MinecraftClient.getInstance();
    private final CommunityBrowserModel model = new CommunityBrowserModel();
    private final HudTextInput custom = new HudTextInput(), tags = new HudTextInput();
    private final HudTextInput recipeName=new HudTextInput();
    private final HudTextInput[] recipeSizes={new HudTextInput(),new HudTextInput(),new HudTextInput()};
    private String recipeReceipt;
    private final List<ButtonWidget> buttons = new ArrayList<>();
    private final List<InputBounds> inputs = new ArrayList<>();
    private int typeIndex, top, bottom;
    private String selected, localError = "";
    private Object renderedPage;
    private List<CommunityBrowserModel.Row> rows = List.of();
    private boolean hasNext;
    private record InputBounds(HudTextInput input,int x,int y,int w) {}
    private interface Action { void run() throws IOException; }
    CommunityLibraryPane() { custom.setMaxLength(64); tags.setMaxLength(1024); recipeName.setMaxLength(64); for(var size:recipeSizes) size.setMaxLength(6); }
    void leave() {
        if(!ClientCommunityPlacement.active()) ClientCommunityTasks.cancel(); blur(); buttons.clear(); inputs.clear(); model.clear();
        selected=null; renderedPage=null; rows=List.of(); hasNext=false; localError=""; recipeReceipt=null;
    }
    private List<HudTextInput> fields() { return List.of(custom,tags,recipeName,recipeSizes[0],recipeSizes[1],recipeSizes[2]); }
    void blur() { fields().forEach(f -> f.setFocused(false)); }
    boolean focused() { return fields().stream().anyMatch(HudTextInput::isFocused); }
    void key(int key,int modifiers) { fields().forEach(f -> f.keyPressed(key,modifiers)); }
    void character(char chr) { fields().forEach(f -> f.charTyped(chr)); }
    private boolean busy() {
        return switch (ClientCommunityTasks.STATE.phase()) {
            case OPENING, UPLOADING, WAITING, FETCHING, CLOSING -> true;
            default -> false;
        };
    }
    private boolean usable() {
        var state=ClientCommunityTasks.STATE;
        return state.sessionId()!=null && !busy() && state.phase()!=CommunityTaskClientState.Phase.ERROR;
    }
    private void act(Action action) {
        blur(); localError="";
        try { action.run(); } catch (IOException | IllegalArgumentException invalid) { localError="操作未完成，请检查筛选条件、名称或尺寸"; }
    }
    int draw(DrawContext ctx,int x,int w,int y,int clipTop,int clipBottom) {
        top=clipTop; bottom=clipBottom; buttons.clear(); inputs.clear();
        var state=ClientCommunityTasks.STATE;
        if(state.installed()==null) { recipeName.setFocused(false); for(var size:recipeSizes) size.setFocused(false); }
        if (renderedPage!=state.result()) {
            selected=null; renderedPage=state.result();
            rows=CommunityBrowserModel.rows(state.result()); hasNext=CommunityBrowserModel.hasNext(state.result());
        }
        y=line(ctx,"社区构件库",x,y,w);
        y=line(ctx,"由服务器连接已配置的社区服务",x,y,w);
        y=button(ctx, state.sessionId()==null || state.phase()==CommunityTaskClientState.Phase.ERROR ? "连接 / 重试" : "重新连接",x,y,w,!busy(),() -> { model.clear(); selected=null; ClientCommunityTasks.open(); });
        y=line(ctx,"状态："+status(),x,y,w);
        if (!localError.isEmpty()) y=line(ctx,localError,x,y,w);
        if (!state.error().isEmpty()) y=line(ctx,error(state.error())+"（"+state.error()+"）",x,y,w);
        y=button(ctx,"取消任务 / 断开",x,y,w,state.sessionId()!=null || busy(),() -> { ClientCommunityTasks.cancel(); model.clear(); selected=null; });
        String type=typeIndex==0 ? "全部" : typeIndex<=ComponentTypes.PRESETS.size() ? ComponentTypes.PRESETS.get(typeIndex-1).type().label() : "自定义";
        y=button(ctx,"类型："+type,x,y,w,!busy(),() -> typeIndex=(typeIndex+1)%(ComponentTypes.PRESETS.size()+2));
        if (typeIndex==ComponentTypes.PRESETS.size()+1) {
            y=line(ctx,"自定义类型名称（与捕获时一致）",x,y,w); y=input(ctx,custom,x,y,w);
        }
        y=line(ctx,"标签（逗号分隔，必须全部匹配）",x,y,w); y=input(ctx,tags,x,y,w);
        y=button(ctx,"搜索",x,y,w,usable(),() -> {
            String key=typeIndex==0 ? null : typeIndex<=ComponentTypes.PRESETS.size() ? ComponentTypes.PRESETS.get(typeIndex-1).type().key() : ComponentTypes.custom(custom.getText()).key();
            ClientCommunityTasks.search(model.search(key,tags.getText())); selected=null;
        });
        y=line(ctx,"当前服务支持类型与标签精确筛选",x,y,w);
        if (state.result()!=null) {
            y=line(ctx,rows.isEmpty() ? "本页没有匹配构件" : "本页 "+rows.size()+" 项（相同内容已归组）",x,y,w);
            y=button(ctx,"下一页（沿用已提交条件）",x,y,w,usable() && hasNext,() -> { ClientCommunityTasks.search(model.nextPage(state.result())); selected=null; });
        }
        for (var row:rows) {
            y=button(ctx,(Objects.equals(selected,row.releaseId()) ? "● " : "○ ")+row.title(),x,y,w,usable(),() -> selected=row.releaseId());
            if (!Objects.equals(selected,row.releaseId())) continue;
            y=line(ctx,row.title(),x,y,w);
            y=line(ctx,"发布标识："+row.releaseId(),x,y,w);
            y=line(ctx,"类型："+row.type(),x,y,w);
            y=line(ctx,"作者声明："+row.author(),x,y,w);
            y=line(ctx,"许可声明："+row.license(),x,y,w);
            y=line(ctx,"训练授权声明："+switch(row.trainingPermission()) { case "ALLOWED" -> "允许"; case "DENIED" -> "禁止"; default -> "未声明"; },x,y,w);
            y=line(ctx,"包大小："+row.bytes()+" 字节",x,y,w);
            y=line(ctx,"游戏兼容性与实际适配尚未验证",x,y,w);
            y=button(ctx,"下载至私人缓存",x,y,w,usable(),() -> ClientCommunityTasks.download(row.releaseId(),false));
            y=button(ctx,"续传至私人缓存",x,y,w,usable(),() -> ClientCommunityTasks.download(row.releaseId(),true));
        }
        if (!state.receiptId().isEmpty() && state.installed()==null) {
            y=line(ctx,"下载已校验并缓存；尚未入库",x,y,w);
            y=button(ctx,"查看入库信息",x,y,w,usable() && state.view()!=null && "DOWNLOAD".equals(state.view().kind()),ClientCommunityTasks::previewInstall);
        }
        if(state.preview()!=null) {
            var p=state.preview();
            y=line(ctx,"待入库："+CommunityBrowserModel.display(p.name(),256),x,y,w);
            y=line(ctx,"发布："+p.authority()+" / "+p.releaseId(),x,y,w);
            y=line(ctx,"尺寸 X/Y/Z："+p.width()+" / "+p.height()+" / "+p.depth()+"；方块 "+p.blocks(),x,y,w);
            y=line(ctx,"作者声明："+CommunityBrowserModel.display(p.author(),256),x,y,w);
            y=line(ctx,"许可声明："+CommunityBrowserModel.display(p.license(),2048),x,y,w);
            y=line(ctx,"训练授权声明："+switch(p.trainingPermission()) { case "ALLOWED" -> "允许"; case "DENIED" -> "禁止"; default -> "未声明"; },x,y,w);
            y=line(ctx,"仅写本人私人库，相同内容会复用并保留来源；游戏运行适配尚未验证",x,y,w);
            y=line(ctx,"确认后若入库已开始，取消或断线不保证回滚",x,y,w);
            y=button(ctx,"确认入私人库",x,y,w,usable(),() -> {
                ClientCommunityTasks.confirmInstall(); prepareRecipeFields(p);
            });
        }
        if(state.installed()!=null) {
            y=line(ctx,(state.restoredSource()==null ? "已入私人库：" : "已恢复至私人库：")+CommunityBrowserModel.display(state.installed().name(),256),x,y,w);
            y=line(ctx,"本地标识："+state.installed().assetId(),x,y,w);
            if(state.restoredSource()!=null) {
                var restored=state.restoredSource(); if(!Objects.equals(recipeReceipt,restored.receiptId())) prepareRecipeFields(restored);
                y=line(ctx,"本次恢复来自本人收藏，未请求远端",x,y,w);
                y=line(ctx,"历史来源："+restored.authority()+" / "+restored.releaseId(),x,y,w);
                y=line(ctx,"作者声明："+CommunityBrowserModel.display(restored.author(),256),x,y,w);
                y=line(ctx,"许可声明："+CommunityBrowserModel.display(restored.license(),2048),x,y,w);
                y=line(ctx,"训练授权声明："+switch(restored.trainingPermission()) { case "ALLOWED" -> "允许"; case "DENIED" -> "禁止"; default -> "未声明"; },x,y,w);
                y=line(ctx,"恢复的是原件；收藏的适配结果仍保留在本地配方中",x,y,w);
            }
            y=line(ctx,"来源收据已保留，未自动加载或放置",x,y,w);
            y=button(ctx,"加载所选来源用于预览",x,y,w,usable(),() -> {
                ClientCommunityPlacement.activate(); localError="正在加载同内容构件；请切换构件工具，放置前仍须确认预览";
            });
            y=line(ctx,"加载前请先刷新本地构件库；普通加载会清除来源绑定",x,y,w);
            if(Objects.equals(recipeReceipt,state.receiptId())) {
                y=line(ctx,"收藏所选来源的尺寸配方",x,y,w);
                y=line(ctx,"配方名称",x,y,w); y=input(ctx,recipeName,x,y,w);
                for(int i=0;i<3;i++) { y=line(ctx,new String[]{"X 宽度","Y 高度","Z 深度"}[i],x,y,w); y=input(ctx,recipeSizes[i],x,y,w); }
                y=line(ctx,"目标尺寸须符合作者规则；写入开始后取消不保证回滚",x,y,w);
                y=button(ctx,"收藏当前尺寸配方",x,y,w,usable(),() -> ClientCommunityTasks.saveRecipe(new com.formacraft.common.component.ComponentRecipeLibrary.Spec(
                        recipeName.getText(),Integer.parseInt(recipeSizes[0].getText()),Integer.parseInt(recipeSizes[1].getText()),Integer.parseInt(recipeSizes[2].getText()))));
            }
            if(state.recipe()!=null) {
                y=line(ctx,"配方已收藏："+CommunityBrowserModel.display(state.recipe().name(),64),x,y,w);
                y=line(ctx,"返回本地库的收藏配方视图可重放或恢复原件",x,y,w);
            }
            y=button(ctx,"刷新本地构件库",x,y,w,true,() -> com.formacraft.client.network.FormaCraftClientNetworking.sendComponentCatalogRequest());
        }
        return line(ctx,"缓存下载不会自动入库或放置",x,y+4,w);
    }
    private int input(DrawContext ctx,HudTextInput input,int x,int y,int w) {
        input.render(ctx,x,y,w,14); inputs.add(new InputBounds(input,x,y,w)); return y+18;
    }
    private void prepareRecipeFields(com.formacraft.common.component.community.CommunityInstallDocument.Preview p) {
        recipeReceipt=p.receiptId(); recipeName.setText(CommunityBrowserModel.display(p.name(),32)+" 配方");
        int[] sizes={p.width(),p.height(),p.depth()}; for(int i=0;i<3;i++) recipeSizes[i].setText(Integer.toString(sizes[i]));
    }
    private int button(DrawContext ctx,String label,int x,int y,int w,boolean enabled,Action action) {
        String shown=client.textRenderer.trimToWidth(label,Math.max(1,w-8));
        var button=ButtonWidget.builder(Text.literal(shown),b -> act(action)).dimensions(x,y,w,16).build();
        button.active=enabled; buttons.add(button);
        button.render(ctx,(int)(client.mouse.getX()/client.getWindow().getScaleFactor()),(int)(client.mouse.getY()/client.getWindow().getScaleFactor()),0f);
        return y+20;
    }
    private int line(DrawContext ctx,String value,int x,int y,int w) {
        for(var line:client.textRenderer.wrapLines(Text.literal(value),Math.max(1,w))) {
            ctx.drawTextWithShadow(client.textRenderer,line,x,y,0xFFCCCCCC); y+=12;
        }
        return y+2;
    }
    boolean click(double x,double y,int button) {
        if(button!=0 || y<top || y>=bottom) return false;
        blur();
        for(var input:inputs) if(y>=input.y && y<input.y+14 && x>=input.x && x<input.x+input.w) {
            input.input.mouseClicked(x,y,input.x,input.y,input.w,14); return true;
        }
        var click=new Click(x,y,new MouseInput(button,0));
        for(var widget:buttons) if(com.formacraft.client.ui.widget.HudClickSupport.click(widget,click)) return true;
        return true;
    }
    private String status() {
        var state=ClientCommunityTasks.STATE;
        return switch(state.phase()) {
            case CLOSED -> "未连接"; case OPENING -> "正在连接"; case IDLE -> "已连接";
            case UPLOADING -> "正在提交搜索"; case FETCHING -> "正在接收结果";
            case WAITING -> state.view()==null ? "正在执行任务" : switch(state.view().kind()) {
                case "PREVIEW" -> "正在校验入库信息"; case "INSTALL" -> "正在入私人库"; case "RESTORE" -> "正在恢复所选来源"; case "RECIPE" -> "正在校验并收藏配方"; case "DOWNLOAD" -> "正在下载并校验"; default -> "正在执行任务";
            };
            case READY -> state.error().equals("recipe_failed") ? "配方未保存，可修改后重试" : "操作完成"; case ERROR -> "操作失败"; case CLOSING -> "正在关闭会话";
        };
    }
    private static String error(String code) {
        return switch(code) {
            case "community_disabled" -> "服务器尚未配置社区服务";
            case "server_unsupported" -> "服务器不支持社区构件库";
            case "cursor_expired", "catalog_expired", "stale_catalog" -> "搜索结果已过期，请重新搜索";
            case "timeout", "request_timeout" -> "请求超时，请重新连接";
            case "connection_lost" -> "连接中断";
            case "preview_expired", "stale_preview", "stale_download" -> "入库确认已失效，请重新下载并查看信息";
            case "stale_install" -> "所选来源任务已失效，请重新入库后操作";
            case "recipe_failed" -> "配方未保存，请检查尺寸与作者规则，或查看服务器日志";
            case "restore_failed", "invalid_recipe" -> "原件恢复失败，请检查本人收藏或服务器日志";
            case "environment_mismatch" -> "当前环境不满足来源包要求";
            case "unsupported_capability", "unsupported_constraint" -> "服务暂不支持此筛选条件";
            default -> "社区操作失败，可重新连接后重试";
        };
    }
}
