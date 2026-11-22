package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAiExplanation;
import com.formacraft.client.ui.panel.capture.ComponentCapturePhaseHeaders;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAnchorSection;
import com.formacraft.client.ui.panel.capture.ComponentCaptureHealthCoordinator;
import com.formacraft.client.ui.panel.capture.ComponentCaptureHealthDrawer;
import com.formacraft.client.ui.panel.capture.ComponentCaptureOrientationController;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSelectionController;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSelectionSection;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAiGuardHost;
import com.formacraft.client.ui.panel.capture.ComponentCaptureAiGuardSection;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSemanticHost;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSemanticPreview;
import com.formacraft.client.ui.panel.capture.ComponentCaptureSemanticSection;
import com.formacraft.client.ui.panel.capture.ComponentCaptureThumbnailService;
import com.formacraft.client.ui.panel.capture.ComponentCaptureWorldOverlay;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.client.network.FormaCraftClientNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 鏋勪欢鎷惧彇闈㈡澘锛堢嫭绔嬮潰鏉匡級
 * <p>
 * 鑱岃矗锛? * - 鏄剧ず褰撳墠閫夊尯棰勮
 * - 閰嶇疆鏋勪欢鐨勬墍鏈夊弬鏁帮紙鍩虹淇℃伅銆侀敋鐐规湞鍚戙€佽涔夋爣娉ㄣ€佽繛鎺ヤ綅锛? * - 鏅鸿兘鍒嗘瀽鏋勪欢鐗瑰緛
 * - 淇濆瓨鏋勪欢鍒板簱
 * <p>
 * 宸ヤ綔娴侊細
 * 1. 鐢ㄦ埛鍦ㄤ笘鐣屼腑鐢?SelectionTool 妗嗛€? * 2. 鍒囨崲鍒版闈㈡澘
 * 3. 閰嶇疆鍙傛暟锛堟垨鐐瑰嚮鏅鸿兘鍒嗘瀽锛? * 4. 鐐瑰嚮"淇濆瓨鍒版瀯浠跺簱"
 * 5. 鑷姩璺宠浆鍒版瀯浠跺簱闈㈡澘骞堕珮浜柊鏋勪欢
 */
public class ComponentCapturePanel extends BasePanel implements ComponentCaptureSemanticHost, ComponentCaptureAiGuardHost {
    private static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
    private static final int LABEL_OFFSET = 18;
    private static final int BUTTON_HEIGHT = 16;
    private static final int THUMBNAIL_SIZE = 120; // 澧炲ぇ鏄剧ず灏哄浠ユ洿濂藉湴灞曠ず鏋勪欢缁嗚妭
    private static final int FIELD_SPACING = 28;
    private static final int STICKY_FOOTER_HEIGHT = 22;

    private static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    // 杈撳叆妗?    private final HudTextInput nameInput = new HudTextInput();
    private final HudTextInput tagsInput = new HudTextInput();
    private final HudTextInput socketIdInput = new HudTextInput();
    private final com.formacraft.client.ui.panel.capture.ComponentCaptureTypeSection typeSection =
            new com.formacraft.client.ui.panel.capture.ComponentCaptureTypeSection(this::typeChanged);
    private java.util.List<ButtonWidget> captureButtons = java.util.List.of();

    // 鍩虹淇℃伅鎸夐挳
    private ButtonWidget categoryButton;
    private ButtonWidget pickAnchorButton;
    private ButtonWidget clearAnchorButton;
    private ButtonWidget hostFaceButton;
    private ButtonWidget anchorOutsideButton;
    private ButtonWidget autoAnchorButton;
    private ButtonWidget facingButton;
    private ButtonWidget mirrorButton;

    // 璇箟鏍囨敞鎸夐挳
    private ButtonWidget semanticSkinButton;
    private ButtonWidget semanticTagOnSaveButton;
    private ButtonWidget semanticStyleButton;
    private ButtonWidget semanticPartButton;

    // 杩炴帴浣嶉厤缃寜閽?    private ButtonWidget socketContextButton;
    private ButtonWidget socketPickOriginButton;
    private ButtonWidget socketFacingButton;
    private ButtonWidget socketAddButton;
    private ButtonWidget socketPreviewButton;
    private ButtonWidget socketClearButton;

    // 鏅鸿兘鍒嗘瀽鎸夐挳
    private ButtonWidget autoFixButton;
    private ButtonWidget undoAutoFixButton;

    // 搴曢儴鎸夐挳
    private ButtonWidget cancelButton;
    private ButtonWidget saveButton;
    
    // 閫夋嫨宸ュ叿鎸夐挳
    private ButtonWidget boxSelectButton;
    private ButtonWidget pointSelectButton;
    private ButtonWidget clearSelectionButton;
    
    // Phase 3: 璇箟閰嶇疆鎸夐挳
    private ButtonWidget advancedOptionsButton;
    private ButtonWidget attachmentModeButton;
    private ButtonWidget directionalityButton;
    private ButtonWidget culturalStyleButton;
    private ButtonWidget geometryArchetypeButton;
    private ButtonWidget setInsideButton;
    private ButtonWidget setOutsideButton;
    private ButtonWidget setBottomButton;
    private ButtonWidget setTopButton;

    // 杈撳叆妗嗚竟鐣岋紙鐢ㄤ簬鐐瑰嚮妫€娴嬶級
    private int nameInputX, nameInputY, nameInputW, nameInputH;
    private int tagsInputX, tagsInputY, tagsInputW, tagsInputH;
    private int socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH;
    private boolean nameInputBoundsValid = false;
    private boolean tagsInputBoundsValid = false;
    private boolean socketIdInputBoundsValid = false;

    private final ComponentCaptureSelectionController selectionController = new ComponentCaptureSelectionController();
    private final com.formacraft.client.ui.panel.capture.ComponentCaptureAdaptationSection adaptationSection =
            new com.formacraft.client.ui.panel.capture.ComponentCaptureAdaptationSection();
    private final ComponentCaptureThumbnailService thumbnailService = new ComponentCaptureThumbnailService();
    private final ComponentCaptureOrientationController orientationController = new ComponentCaptureOrientationController();
    private final ComponentCaptureHealthCoordinator healthCoordinator;
    private final ComponentCaptureSemanticPreview semanticPreview = new ComponentCaptureSemanticPreview();
    private final ComponentCaptureAiExplanation aiExplanation;
    private final ComponentCaptureWorldOverlay worldOverlay;

    // 婊氬姩
    private int scrollY = 0;
    private int maxScrollY = 0;
    
    // Phase 3: 璇箟閰嶇疆鐘舵€?    private com.formacraft.common.component.placement.AttachmentType attachmentMode = 
        com.formacraft.common.component.placement.AttachmentType.NONE;
    
    // 闃舵鎰熺煡鐘舵€?    private CapturePhase currentPhase = CapturePhase.SELECTION;
    private boolean[] phaseCollapsed = new boolean[CapturePhase.getTotalPhases()];
    private final ComponentCapturePhaseHeaders phaseHeaders = new ComponentCapturePhaseHeaders();
    private CapturePhase lastAutoCollapsePhase = null;
    private boolean advancedOptionsExpanded = false;
    
    // 璋冭瘯寮€鍏?    private static final boolean DEBUG_CAPTURE = false; // 璁剧疆涓?true 鍚敤璋冭瘯鏃ュ織

    public ComponentCapturePanel() {
        nameInput.setMaxLength(64);
        nameInput.setPlaceholder("杈撳叆鏋勪欢鍚嶇О锛堝锛氭鏈ㄩ棬锛?);
        
        tagsInput.setMaxLength(256);
        tagsInput.setPlaceholder("杈撳叆鏍囩锛岀敤閫楀彿鍒嗛殧锛堝锛歸ood, modern锛?);
        
        socketIdInput.setMaxLength(64);
        socketIdInput.setPlaceholder("杈撳叆杩炴帴浣?ID锛堝锛歞oor_frame锛?);

        selectionController.setOnSelectionChanged(() -> { thumbnailService.invalidate(); adaptationSection.invalidate(); });
        healthCoordinator = new ComponentCaptureHealthCoordinator(selectionController);
        aiExplanation = new ComponentCaptureAiExplanation(semanticPreview, orientationController);
        worldOverlay = new ComponentCaptureWorldOverlay(selectionController);
    }

    private void ensureWidgets() {
        if (categoryButton != null) return;

        var st = ComponentTool.INSTANCE.getState();

        // 鍩虹淇℃伅鎸夐挳
        categoryButton = ButtonWidget.builder(Text.literal("鏇存敼鎹曡幏绫诲瀷"), b -> { typeSection.open(); scrollY = 0; })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鏋勪欢鍒嗙被
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        杩斿洖椤堕儴閫夋嫨棰勮鎴栬嚜瀹氫箟绫诲瀷
                        
                        鍒嗙被鐢ㄤ簬锛?                        鈥?AI 鏅鸿兘鏀剧疆鏃剁殑璇箟璇嗗埆
                        鈥?鏋勪欢搴撶殑鍒嗙被娴忚
                        鈥?鑷姩妫€娴嬮檮鐫€妯″紡
                        
                        甯歌鍒嗙被锛氶棬銆佺獥銆佹煴瀛愩€佽楗扮瓑""")))
                .build();

        pickAnchorButton = ButtonWidget.builder(Text.literal("馃搷 鐐瑰嚮閫夋嫨閿氱偣"), b -> ComponentTool.INSTANCE.startPickAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆閿氱偣锛堝師鐐癸級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        閿氱偣鏄瀯浠剁殑鍙傝€冨師鐐?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鍙抽敭鐐瑰嚮鏂瑰潡
                        3. 璇ユ柟鍧楀皢鎴愪负閿氱偣
                        
                        寤鸿浣嶇疆锛?                        鈥?闂ㄧ獥锛氬簳閮ㄤ腑蹇?                        鈥?鏌卞瓙锛氬簳閮ㄤ腑蹇?                        鈥?瑁呴グ锛氶檮鐫€鐐?                        鈥?瀹跺叿锛氬墠渚у簳閮ㄤ腑蹇?"")))
                .build();

        clearAnchorButton = ButtonWidget.builder(Text.literal("娓呴櫎閿氱偣"), b -> ComponentTool.INSTANCE.clearAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        娓呴櫎閿氱偣
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        绉婚櫎褰撳墠璁剧疆鐨勯敋鐐?                        
                        娓呴櫎鍚庨渶瑕侀噸鏂拌缃敋鐐?                        鎵嶈兘淇濆瓨鏋勪欢""")))
                .build();

        hostFaceButton = ButtonWidget.builder(Text.literal("閫夋嫨瀹夸富闈?), b -> orientationController.startMarkingHostFace())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        閫夋嫨瀹夸富闈紙澶栧琛ㄩ潰锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹夸富闈㈡槸鏋勪欢涓庡浣撳榻愮殑鍙傝€冮潰
                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鐐瑰嚮澶栧琛ㄩ潰
                        3. 绯荤粺鑷姩璁板綍澶栨硶绾?                        
                        鐢ㄤ簬锛氱獥銆侀棬銆侀洦妫氥€侀槼鍙扮瓑闇€瑕佸榻愬闈㈢殑鏋勪欢""")))
                .build();

        anchorOutsideButton = ButtonWidget.builder(Text.literal("澶栦晶閿氱偣锛氬叧"), b -> {
                    st.captureDraft.anchor.allowOutsideSelection = !st.captureDraft.anchor.allowOutsideSelection;
                    st.syncDraftToState();
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鍏佽澶栦晶閿氱偣锛堢┖姘斿涓婚潰锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        寮€鍚悗鍙皢閿氱偣鏀惧湪閫夊尯澶栦晶鐨勭┖姘旀柟鍧?                        
                        浣跨敤鍦烘櫙锛?                        ? 瀹屽叏澶栧嚫鐨勭獥/瑁呴グ
                        ? 闇€瑕佷互澶栧闈负闆堕潰鐨勬瀯浠?"")))
                .build();

        autoAnchorButton = ButtonWidget.builder(Text.literal("鑷姩閿氱偣锛堝簳閮ㄤ腑蹇冿級"), b -> setAutoAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鑷姩璁剧疆閿氱偣
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        灏嗛敋鐐规斁缃湪鏋勪欢搴曢儴涓績
                        
                        瀵瑰伓鏁板搴︽瀯浠舵洿鍙嬪ソ
                        閫傚悎闂ㄧ獥/鏌卞瓙绛夋瀯浠?"")))
                .build();

        facingButton = ButtonWidget.builder(Text.literal("鏈濆悜锛歋OUTH"), b -> cycleFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鏋勪欢鏈濆悜
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟鏋勪欢鐨?姝ｉ潰"鏈濆悜
                        
                        鐢ㄩ€旓細
                        鈥?AI 鏀剧疆鏃惰嚜鍔ㄦ棆杞?                        鈥?纭繚闂ㄧ獥鏈濆悜姝ｇ‘
                        鈥?瀵圭О鏋勪欢鐨勯暅鍍忓弬鑰?                        
                        鐐瑰嚮寰幆鍒囨崲锛氬寳/涓?鍗?瑗?"")))
                .build();

        mirrorButton = ButtonWidget.builder(Text.literal("闀滃儚锛歂ONE"), b -> cycleMirror())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        闀滃儚妯″紡
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鎺у埗鏋勪欢鐨勯暅鍍忚涓?                        
                        閫夐」锛?                        鈥?NONE锛氫笉闀滃儚
                        鈥?X锛氭部 X 杞撮暅鍍?                        鈥?Z锛氭部 Z 杞撮暅鍍?                        
                        鐢ㄤ簬鍒涘缓瀵圭О鍙樹綋""")))
                .build();

        // 璇箟鏍囨敞鎸夐挳
        semanticSkinButton = ButtonWidget.builder(Text.literal("鏉愯川锛氬師鏍?), b -> ComponentTool.INSTANCE.toggleSemanticSkin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鏉愯川妯″紡锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鎺у埗鏂瑰潡鏉愯川鐨勫鐞嗘柟寮?                        
                        鍘熸牱鏂瑰潡锛?                        鈥?淇濈暀绮剧‘鐨勬柟鍧楃被鍨?                        鈥?閫傚悎鐗瑰畾璁捐鐨勬瀯浠?                        
                        璇箟鎹㈢毊锛?                        鈥?AI 鍙牴鎹鏍兼浛鎹㈡潗璐?                        鈥?閫傚悎鍙彉椋庢牸鐨勬瀯浠?"")))
                .build();

        semanticTagOnSaveButton = ButtonWidget.builder(Text.literal("瀛樿涔夛細寮€"), b -> st.semanticTagOnSave = !st.semanticTagOnSave)
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        淇濆瓨璇箟鏍囨敞锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        淇濆瓨鏃朵负姣忎釜鏂瑰潡娣诲姞璇箟淇℃伅
                        
                        寮€鍚悗锛?                        鈥?鑷姩鏍囨敞鏂瑰潡鐨勫姛鑳介儴浣?                        鈥?AI 鍙洿鏅鸿兘鍦拌皟鏁存瀯浠?                        鈥?鏀寔椋庢牸椹卞姩鐨勬潗璐ㄦ浛鎹?                        
                        鎺ㄨ崘锛氶€氱敤鏋勪欢寮€鍚?"")))
                .build();

        semanticStyleButton = ButtonWidget.builder(Text.literal("椋庢牸锛欴EFAULT"), b -> ComponentTool.INSTANCE.cycleSemanticStyle())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        寤虹瓚椋庢牸锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟鏋勪欢鐨勫缓绛戦鏍?                        
                        椋庢牸绫诲瀷锛?                        鈥?DEFAULT锛氶粯璁?娣峰悎
                        鈥?MEDIEVAL锛氫腑涓栫邯
                        鈥?MODERN锛氱幇浠?                        鈥?ASIAN锛氫簹娲查鏍?                        
                        鐢ㄤ簬 AI 椋庢牸鍖归厤""")))
                .build();

        semanticPartButton = ButtonWidget.builder(Text.literal("璇箟锛欰UTO"), b -> ComponentTool.INSTANCE.cycleSemanticPart())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璇箟閮ㄤ綅锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍囨敞鏋勪欢鐨勫姛鑳介儴浣?                        
                        AUTO锛氳嚜鍔ㄦ娴?                        鎵嬪姩閫夐」锛?                        鈥?FRAME锛氭鏋?杈规
                        鈥?FILL锛氬～鍏?涓讳綋
                        鈥?DETAIL锛氳楗扮粏鑺?                        
                        鐢ㄤ簬鏅鸿兘鏉愯川鏇挎崲""")))
                .build();

        // 杩炴帴浣嶉厤缃寜閽?        socketContextButton = ButtonWidget.builder(Text.literal("杩炴帴浣嶄笂涓嬫枃: WALL"), b -> ComponentTool.INSTANCE.cycleSocketType())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        杩炴帴浣嶄笂涓嬫枃锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟杩炴帴浣嶇殑闄勭潃绫诲瀷
                        
                        绫诲瀷锛?                        鈥?WALL锛氬闈紙闂ㄧ獥锛?                        鈥?FLOOR锛氬湴鏉匡紙瀹跺叿锛?                        鈥?CEILING锛氬ぉ鑺辨澘锛堝悐鐏級
                        鈥?EDGE锛氳竟缂橈紙鏍忔潌锛?                        
                        AI 灏嗘牴鎹笂涓嬫枃鏅鸿兘鏀剧疆""")))
                .build();

        socketPickOriginButton = ButtonWidget.builder(Text.literal("鐐归€夊師鐐?), b -> ComponentTool.INSTANCE.startPickSocketOrigin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆杩炴帴浣嶅師鐐癸紙楂樼骇锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        杩炴帴浣嶆槸鏋勪欢鐨勫祵鍏ヤ綅缃?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鍙抽敭鐐瑰嚮
                        3. 璁剧疆杩炴帴浣嶇殑鍙傝€冪偣
                        
                        渚嬪锛氶棬妗嗙殑搴曢儴涓績""")))
                .build();

        socketFacingButton = ButtonWidget.builder(Text.literal("鏈濆悜: SOUTH"), b -> ComponentTool.INSTANCE.cycleSocketFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        杩炴帴浣嶆湞鍚戯紙楂樼骇锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟杩炴帴浣嶇殑鏀剧疆鏂瑰悜
                        
                        纭繚锛?                        鈥?闂ㄧ獥鏈濆悜姝ｇ‘
                        鈥?闄勭潃闈㈠榻?                        鈥?澶氫釜杩炴帴浣嶅崗璋冧竴鑷?                        
                        鐐瑰嚮寰幆鍒囨崲鏂瑰悜""")))
                .build();

        socketAddButton = ButtonWidget.builder(Text.literal("娣诲姞杩炴帴浣?), b -> ComponentTool.INSTANCE.addSocket())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        娣诲姞杩炴帴浣嶏紙楂樼骇锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        灏嗗綋鍓嶉厤缃殑杩炴帴浣嶆坊鍔犲埌鏋勪欢
                        
                        涓€涓瀯浠跺彲浠ユ湁澶氫釜杩炴帴浣?                        渚嬪锛氬浣撳彲浠ユ湁澶氫釜闂ㄧ獥浣嶇疆
                        
                        娣诲姞鍚庡湪鍒楄〃涓樉绀?"")))
                .build();

        socketPreviewButton = ButtonWidget.builder(Text.literal("棰勮"), b -> ComponentTool.INSTANCE.toggleSocketPreview(client))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        棰勮杩炴帴浣嶏紙楂樼骇锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鍦ㄤ笘鐣屼腑鏄剧ず杩炴帴浣嶇殑锛?                        鈥?浣嶇疆鏍囪
                        鈥?鏈濆悜绠ご
                        鈥?澶у皬鍖哄煙
                        
                        甯姪楠岃瘉杩炴帴浣嶉厤缃纭?"")))
                .build();

        socketClearButton = ButtonWidget.builder(Text.literal("娓呯┖杩炴帴浣?), b -> ComponentTool.INSTANCE.clearSockets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        娓呯┖鎵€鏈夎繛鎺ヤ綅锛堥珮绾э級
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        绉婚櫎鎵€鏈夊凡娣诲姞鐨勮繛鎺ヤ綅
                        
                        娉ㄦ剰锛氭鎿嶄綔涓嶅彲鎾ら攢
                        娓呯┖鍚庨渶瑕侀噸鏂伴厤缃?"")))
                .build();

        // 鑷姩淇鎸夐挳
        autoFixButton = ButtonWidget.builder(Text.literal("馃 鑷姩淇"), b -> healthCoordinator.runAutoFix(client))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鑷姩淇鏋勪欢闂
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍规嵁鍋ュ悍妫€鏌ョ粨鏋滆嚜鍔ㄤ慨澶嶅彲淇鐨勯棶棰?                        
                        淇鍐呭锛?                        鈥?鑷姩璁剧疆閿氱偣锛堝鏋滅己澶憋級
                        鈥?璋冩暣閿氱偣浣嶇疆锛堝鏋滀笉鍚堢悊锛?                        鈥?鍏朵粬鍙嚜鍔ㄤ慨澶嶇殑闂
                        
                        娉ㄦ剰锛氬彧淇鏍囪涓?鍙嚜鍔ㄤ慨澶?鐨勯棶棰?"")))
                .build();

        undoAutoFixButton = ButtonWidget.builder(Text.literal("鈫?鎾ら攢淇"), b -> healthCoordinator.undoAutoFix())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鎾ら攢涓婁竴娆¤嚜鍔ㄤ慨澶?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鎭㈠鍒颁慨澶嶅墠鐨勭姸鎬?                        """)))
                .build();

        // 搴曢儴鎸夐挳
        cancelButton = ButtonWidget.builder(Text.literal("閲嶇疆"), b -> {
                    if (st.isCaptureSavePending()) { com.formacraft.client.component.ClientComponentImport.cancel(); return; }
                    st.pickingAnchor = false;
                    st.cancelCapture();
                    clearSelection();
                    advancedOptionsExpanded = false;
                    st.beginCapture();
                    syncLocalFromState();
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        閲嶇疆鏋勪欢鎷惧彇
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        娓呯┖褰撳墠閫夊尯涓庨厤缃?                        鐣欏湪鎷惧彇闈㈡澘閲嶆柊寮€濮?                        
                        宸查厤缃殑鍐呭灏嗕涪澶?"")))
                .build();

        saveButton = ButtonWidget.builder(Text.literal("鉁?淇濆瓨鍒版瀯浠跺簱"), b -> saveComponent())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        淇濆瓨鏋勪欢鍒板簱
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        灏嗘瀯浠朵繚瀛樺埌鍏ㄥ眬鏋勪欢搴?                        
                        淇濆瓨鍐呭锛?                        鈥?鏂瑰潡鏁版嵁鍜岀粨鏋?                        鈥?閿氱偣鍜屾湞鍚?                        鈥?鍒嗙被鍜屾爣绛?                        鈥?缂╃暐鍥鹃瑙?                        鈥?杩炴帴浣嶉厤缃紙濡傛灉鏈夛級
                        
                        淇濆瓨鍚庤嚜鍔ㄨ烦杞埌鏋勪欢搴?"")))
                .build();
        
        // 閫夋嫨宸ュ叿鎸夐挳
        boxSelectButton = ButtonWidget.builder(Text.literal("馃摝 妗嗛€?), b -> setSelectionMode(ComponentSelectionMode.BOX_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        妗嗛€夋ā寮?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鎷栨嫿妗嗛€夊尯鍩燂紙鏈夊疄鏃堕瑙堬級
                        
                        浣跨敤鏂规硶锛?                        1. 婵€娲绘妯″紡
                        2. 鍦ㄤ笘鐣屼腑宸﹂敭鎷栨嫿
                        3. 鐪嬪埌钃濊壊棰勮妗?                        4. 閲婃斁榧犳爣瀹屾垚閫夋嫨
                        
                        鎻愮ず锛氶€傚悎蹇€熸閫夊畬鏁寸粨鏋?"")))
                .build();
        
        pointSelectButton = ButtonWidget.builder(Text.literal("馃憜 鐐归€?), b -> setSelectionMode(ComponentSelectionMode.POINT_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鐐归€夋ā寮?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鍗曚釜鏂瑰潡绮剧‘閫夋嫨
                        
                        浣跨敤鏂规硶锛?                        鈥?鐐瑰嚮鏈€夋柟鍧?鈫?鍔犻€?                        鈥?鐐瑰嚮宸查€夋柟鍧?鈫?鍑忛€?                        鈥?Ctrl+鐐瑰嚮 鈫?寮哄埗鍔犻€?                        
                        鎻愮ず锛氶€傚悎绮剧粏璋冩暣鍜屼笉瑙勫垯閫夊尯""")))
                .build();
        
        clearSelectionButton = ButtonWidget.builder(Text.literal("馃棏锔?娓呴櫎"), b -> clearSelection())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        娓呴櫎閫夊尯
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        涓€閿竻绌哄綋鍓嶉€夊尯
                        
                        浣跨敤鍦烘櫙锛?                        鈥?閲嶆柊寮€濮嬮€夋嫨
                        鈥?娓呴櫎閿欒鐨勯€夊尯
                        鈥?蹇€熼噸缃?                        
                        鎻愮ず锛氭竻闄ゅ悗闇€瑕侀噸鏂伴€夋嫨""")))
                .build();
        
        // Phase 3: 璇箟閰嶇疆鎸夐挳
        advancedOptionsButton = ButtonWidget.builder(Text.literal("鈻?楂樼骇閫夐」"), b -> advancedOptionsExpanded = !advancedOptionsExpanded)
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("灞曞紑闄勭潃銆佹柟鍚戞€т笌璇箟鏍囨敞绛夐珮绾ч€夐」")))
                .build();

        attachmentModeButton = ButtonWidget.builder(
                Text.literal("闄勭潃: " + getAttachmentModeDisplay()), 
                b -> cycleAttachmentMode()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        闄勭潃妯″紡
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟鏋勪欢濡備綍闄勭潃鍒板缓绛戜笂
                        
                        妯″紡锛?                        鈥?鏃犻檮鐫€ - 鐙珛鏋勪欢锛堟煴瀛愩€侀洉濉戯級
                        鈥?鍦伴潰 - 鍦版澘瑁呴グ
                        鈥?澧欓潰 - 璐村瑁呴グ銆佸榫?                        鈥?澧欎綋 - 闂ㄣ€佺獥锛堣嚜鍔ㄥ紑娲烇級
                        鈥?灞嬮潰 - 鑰佽檸绐?                        鈥?灞嬫獝 - 椋炴獝
                        鈥?灞嬭剨 - 鑴婂吔
                        鈥?杈圭紭 - 鏍忔潌銆佹姢鏍?                        鈥?杞 - 闃冲彴銆佸瑙掕楗?                        
                        鐐瑰嚮寰幆鍒囨崲""")))
                .build();
        
        directionalityButton = ButtonWidget.builder(
                Text.literal("鏂瑰悜: " + orientationController.getDirectionalityMode().getDisplayName()), 
                b -> cycleDirectionality()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鏂瑰悜鎬?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        瀹氫箟鏋勪欢鏄惁鏈夋柟鍚戞€?                        
                        妯″紡锛?                        鈥?鏃犳柟鍚?- 浠绘剰鏃嬭浆
                        鈥?鍐呭 - 鏈夊唴澶栦晶锛堥棬銆佺獥锛?                        鈥?涓婁笅 - 鏈変笂涓嬬锛堟ゼ姊級
                        鈥?鍙屽悜 - 鍚屾椂鏈夊唴澶栧拰涓婁笅
                        
                        鐐瑰嚮寰幆鍒囨崲""")))
                .build();
        
        setInsideButton = ButtonWidget.builder(
                Text.literal("馃彔 璁惧唴渚?), 
                b -> orientationController.startMarkingInside()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆鍐呬晶鏍囪
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍囪鏋勪欢鐨勫唴渚ф柟鍚?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鐐瑰嚮鍐呬晶鏂瑰潡
                        3. 绯荤粺鑷姩璁＄畻鏈濆悜
                        
                        鐢ㄤ簬锛氶棬銆佺獥绛夋湁鍐呭涔嬪垎鐨勬瀯浠?"")))
                .build();
        
        setOutsideButton = ButtonWidget.builder(
                Text.literal("馃實 璁惧渚?), 
                b -> orientationController.startMarkingOutside()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆澶栦晶鏍囪
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍囪鏋勪欢鐨勫渚ф柟鍚?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鐐瑰嚮澶栦晶鏂瑰潡
                        3. 绯荤粺鑷姩璁＄畻鏈濆悜
                        
                        鐢ㄤ簬锛氶棬銆佺獥绛夋湁鍐呭涔嬪垎鐨勬瀯浠?"")))
                .build();
        
        setBottomButton = ButtonWidget.builder(
                Text.literal("猬囷笍 璁惧簳绔?), 
                b -> orientationController.startMarkingBottom()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆搴曠鏍囪
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍囪鏋勪欢鐨勫簳绔綅缃?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鐐瑰嚮搴曠鏂瑰潡
                        3. 绯荤粺鑷姩璁＄畻涓婁笅鏈濆悜
                        
                        鐢ㄤ簬锛氭ゼ姊€佹瀛愮瓑鏈変笂涓嬩箣鍒嗙殑鏋勪欢""")))
                .build();
        
        setTopButton = ButtonWidget.builder(
                Text.literal("猬嗭笍 璁鹃《绔?), 
                b -> orientationController.startMarkingTop()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        璁剧疆椤剁鏍囪
                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鏍囪鏋勪欢鐨勯《绔綅缃?                        
                        浣跨敤鏂规硶锛?                        1. 鐐瑰嚮姝ゆ寜閽?                        2. 鍦ㄤ笘鐣屼腑鐐瑰嚮椤剁鏂瑰潡
                        3. 绯荤粺鑷姩璁＄畻涓婁笅鏈濆悜
                        
                        鐢ㄤ簬锛氭ゼ姊€佹瀛愮瓑鏈変笂涓嬩箣鍒嗙殑鏋勪欢""")))
                .build();

        culturalStyleButton = ButtonWidget.builder(Text.literal("鏂囧寲椋庢牸: 鑷姩"), b -> semanticPreview.cycleCulturalStyle())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鏂囧寲椋庢牸锛圓I 妫€绱㈢敤锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        鈥?鑷姩锛氭牴鎹爣绛句笌鏉愯川鎺ㄦ柇
                        鈥?鎵嬪姩锛氳鐩栬嚜鍔ㄦ帹鏂粨鏋?                        
                        褰卞搷 AI 閫夋嫨鏋勪欢鏃剁殑椋庢牸鍖归厤""")))
                .build();

        geometryArchetypeButton = ButtonWidget.builder(Text.literal("鍑犱綍鍘熷瀷: 鑷姩"), b -> semanticPreview.cycleGeometryArchetype())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        鍑犱綍鍘熷瀷锛堝舰鎬佹棌锛?                        鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣鈹佲攣
                        ORNAMENT / FRAME / LINEAR 绛?                        
                        甯姪 AI 鍖哄垎鏂楁嫳銆佺獥濂椼€佹爮鏉嗙瓑褰㈡€?"")))
                .build();
        captureButtons = java.util.List.of(categoryButton, pickAnchorButton, clearAnchorButton, hostFaceButton,
                anchorOutsideButton, autoAnchorButton, facingButton, mirrorButton, semanticSkinButton,
                semanticTagOnSaveButton, semanticStyleButton, semanticPartButton, socketContextButton,
                socketPickOriginButton, socketFacingButton, socketAddButton, socketPreviewButton, socketClearButton,
                autoFixButton, undoAutoFixButton, cancelButton, saveButton,
                boxSelectButton, pointSelectButton, clearSelectionButton, advancedOptionsButton, attachmentModeButton,
                directionalityButton, culturalStyleButton, geometryArchetypeButton, setInsideButton, setOutsideButton,
                setBottomButton, setTopButton);
    }
    
    /**
     * 鑾峰彇闄勭潃妯″紡鐨勬樉绀哄悕绉?     */
    @Override
    public String getAttachmentModeDisplay() {
        return switch (attachmentMode) {
            case NONE -> "鏃犻檮鐫€";
            case FLOOR -> "鍦伴潰";
            case WALL_SURFACE -> "澧欓潰";
            case WALL_OPENING -> "澧欎綋"; // 闂ㄣ€佺獥绛変細鍦ㄥ浣撲笂寮€娲炵殑鏋勪欢
            case ROOF_SURFACE -> "灞嬮潰";
            case ROOF_EDGE -> "灞嬫獝";
            case ROOF_RIDGE -> "灞嬭剨";
            case EDGE -> "杈圭紭";
            case CORNER -> "杞";
        };
    }
    
    /**
     * 寰幆鍒囨崲闄勭潃妯″紡
     */
    private void cycleAttachmentMode() {
        com.formacraft.common.component.placement.AttachmentType[] values = 
            com.formacraft.common.component.placement.AttachmentType.values();
        int index = attachmentMode.ordinal();
        attachmentMode = values[(index + 1) % values.length];
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        draft.host.manualAttachment = true;
        syncPlacementHintsToState();
        semanticPreview.invalidate();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 鍒囨崲闄勭潃妯″紡: {}", attachmentMode);
        }
    }
    
    /**
     * 寰幆鍒囨崲鏂瑰悜鎬фā寮?     */
    private void cycleDirectionality() {
        orientationController.cycleDirectionality();
        semanticPreview.invalidate();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 鍒囨崲鏂瑰悜鎬? {}",
                    orientationController.getDirectionalityMode().getDisplayName());
        }
    }

    /**
     * 鍚屾 UI 鐨勯檮鐫€/鏂瑰悜鎬ф彁绀哄埌 ComponentToolState
     */
    private void syncPlacementHintsToState() {
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        draft.host.attachment = attachmentMode;
        orientationController.applyDirectionalityToDraft();
    }

    /**
     * 璁剧疆閫夋嫨妯″紡
     */
    private void setSelectionMode(ComponentSelectionMode mode) {
        selectionController.setMode(mode);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();
        if (!ComponentTool.INSTANCE.getState().captureActive) {
            ComponentTool.INSTANCE.getState().beginCapture(); syncLocalFromState();
        }
        captureButtons.forEach(b -> b.visible = false);

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;
        int y = getContentY() + CONTENT_PADDING - scrollY;

        adaptationSection.hide();
        // 閲嶇疆杈圭晫
        nameInputBoundsValid = false;
        tagsInputBoundsValid = false;
        socketIdInputBoundsValid = false;

        drawContentBackground(ctx);

        // 鍚敤瑁佸壀锛堜负搴曢儴鍥哄畾鎸夐挳鐣欏嚭绌洪棿锛屼笌鍐呭眰杈规瀵归綈锛?        int scrollBottom = panelY + panelHeight - STICKY_FOOTER_HEIGHT;
        ctx.enableScissor(contentScissorLeft(), getContentY(), contentScissorRight(), scrollBottom);

        phaseHeaders.reset();

        // 鏍囬
        y = drawWrappedText(ctx, Text.translatable("formacraft.capture.title"), x, y, w, 0xFFFFFFFF);
        y += 4;
        y = typeSection.draw(ctx, client, this::drawWrappedText, getScaledMouseX(), getScaledMouseY(), x, y, w, getContentY(), scrollBottom);
        var captureState = ComponentTool.INSTANCE.getState();
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(captureState) || captureState.isCaptureSavePending()) {
            y = drawWrappedText(ctx, Text.literal(captureState.isCaptureSavePending()
                    ? "姝ｅ湪绛夊緟鏈嶅姟绔繚瀛樼粨鏋溾€? : "鍏堥€夋嫨绫诲瀷锛屽啀瀹氫箟閫夊尯锛涚幇鏈夐€夊尯浼氫繚鐣欍€?), x, y, w, 0xFFAAAAAA);
            finishCaptureLayout(ctx, x, w, y);
            return;
        }
        autoCollapseNonActivePhases();
        
        // 闃舵鎻愮ず锛堥《閮ㄥ急寮曞锛?        y = drawWrappedText(
                ctx,
                Text.translatable(
                        "formacraft.capture.phase_progress",
                        currentPhase.getPhaseNumber(),
                        CapturePhase.getTotalPhases(),
                        Text.translatable(currentPhase.getDescriptionTranslationKey()).getString()
                ),
                x, y, w, 0xFF88FF88);
        y += 6;
        
        // 鍒嗛殧绾?        ctx.fill(x, y, x + w, y + 1, UiTheme.DIVIDER_SUBTLE);
        y += 4;
        
        // ============ 闃舵 1锛氶€夊尯瀹氫箟 ============
        boolean phase1Collapsed = phaseCollapsed[0];
        y = ComponentCaptureSelectionSection.drawSection(
                ctx,
                client,
                selectionController,
                thumbnailService,
                healthCoordinator,
                this::drawWrappedText,
                getScaledMouseX(),
                getScaledMouseY(),
                phase1Collapsed,
                currentPhase == CapturePhase.SELECTION,
                isPhaseComplete(CapturePhase.SELECTION),
                phaseHeaders,
                0,
                boxSelectButton,
                pointSelectButton,
                clearSelectionButton,
                THUMBNAIL_SIZE,
                x,
                y,
                w
        );

        syncPlacementHintsToState();

        // ============ 闃舵 2锛氶敋鐐逛笌鏈濆悜 ============
        boolean phase2Collapsed = phaseCollapsed[1];
        y = ComponentCaptureAnchorSection.drawSection(
                ctx,
                client,
                selectionController,
                thumbnailService,
                healthCoordinator,
                this::drawWrappedText,
                getScaledMouseX(),
                getScaledMouseY(),
                phase2Collapsed,
                currentPhase == CapturePhase.ANCHOR_ORIENTATION,
                isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION),
                !isPhaseComplete(CapturePhase.SELECTION),
                phaseHeaders,
                1,
                pickAnchorButton,
                clearAnchorButton,
                hostFaceButton,
                anchorOutsideButton,
                autoAnchorButton,
                facingButton,
                mirrorButton,
                x,
                y,
                w
        );

        // ============ 闃舵 3锛氭瀯浠惰涔夌‘璁?============
        boolean phase3Collapsed = phaseCollapsed[2];
        y = ComponentCaptureSemanticSection.drawSection(
                ctx,
                client,
                this,
                orientationController,
                semanticPreview,
                this::drawWrappedText,
                nameInput,
                tagsInput,
                getScaledMouseX(),
                getScaledMouseY(),
                phase3Collapsed,
                currentPhase == CapturePhase.SEMANTIC,
                isPhaseComplete(CapturePhase.SEMANTIC),
                !isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION),
                phaseHeaders,
                2,
                categoryButton,
                advancedOptionsButton,
                attachmentModeButton,
                directionalityButton,
                setInsideButton,
                setOutsideButton,
                setBottomButton,
                setTopButton,
                culturalStyleButton,
                geometryArchetypeButton,
                semanticSkinButton,
                semanticTagOnSaveButton,
                semanticStyleButton,
                semanticPartButton,
                x,
                y,
                w
        );

        if (!phase3Collapsed) {
            y = adaptationSection.draw(ctx, client, getScaledMouseX(), getScaledMouseY(),
                    x, y, w, getContentY(), scrollBottom);
        }

        // ============ 闃舵 4锛欰I 浣跨敤淇濋殰 ============
        boolean phase4Collapsed = phaseCollapsed[3];
        y = ComponentCaptureAiGuardSection.drawSection(
                ctx,
                client,
                this,
                selectionController,
                this::drawWrappedText,
                socketIdInput,
                getScaledMouseX(),
                getScaledMouseY(),
                phase4Collapsed,
                currentPhase == CapturePhase.AI_GUARANTEE,
                isPhaseComplete(CapturePhase.AI_GUARANTEE),
                !isPhaseComplete(CapturePhase.SEMANTIC),
                phaseHeaders,
                3,
                socketContextButton,
                socketPickOriginButton,
                socketFacingButton,
                socketAddButton,
                socketPreviewButton,
                socketClearButton,
                x,
                y,
                w
        );
        
        // ============ 鏋勪欢鍋ュ悍鐘舵€佹鏌ワ紙鏂拌璁★細鍋ュ悍鏉?+ 鎶藉眽锛?===========
        if (isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION)) {
            y = ComponentCaptureHealthDrawer.drawSection(
                    ctx,
                    healthCoordinator,
                    this::drawWrappedText,
                    scrollY,
                    x,
                    y,
                    w,
                    getScaledMouseX(),
                    getScaledMouseY(),
                    autoFixButton,
                    undoAutoFixButton
            );
        }
        
        // ============ AI 瑙嗚瑙ｉ噴鍖?============
        if (isPhaseComplete(CapturePhase.SEMANTIC)) {
            y = aiExplanation.renderSection(ctx, client, this::drawWrappedText, x, y, w);
        }

        finishCaptureLayout(ctx, x, w, y);
    }

    private void finishCaptureLayout(DrawContext ctx, int x, int w, int y) {
        if (!nameInputBoundsValid) nameInput.setFocused(false);
        if (!tagsInputBoundsValid) tagsInput.setFocused(false);
        if (!socketIdInputBoundsValid) socketIdInput.setFocused(false);
        // 璁＄畻鏈€澶ф粴鍔?        int contentStartY = getContentY() + CONTENT_PADDING;
        int totalContentHeight = (y + scrollY) - contentStartY;
        int visibleHeight = panelHeight - (contentStartY - panelY) - CONTENT_PADDING - STICKY_FOOTER_HEIGHT;
        maxScrollY = Math.max(0, totalContentHeight - visibleHeight);

        if (scrollY > maxScrollY) scrollY = maxScrollY;
        if (scrollY < 0) scrollY = 0;

        ctx.disableScissor();
        drawStickyFooter(ctx, x, w);
    }

    private void drawStickyFooter(DrawContext ctx, int x, int w) {
        int footerTop = panelY + panelHeight - STICKY_FOOTER_HEIGHT - 4;
        ctx.fill(panelX + 1, footerTop, panelX + panelWidth - 1, panelY + panelHeight - 1, 0xE0101010);
        ctx.fill(x, footerTop, x + w, footerTop + 1, UiTheme.DIVIDER_SUBTLE);

        int half = (w - 4) / 2;
        cancelButton.setPosition(x, footerTop + 4);
        cancelButton.setWidth(half);
        cancelButton.visible = true;
        var st = ComponentTool.INSTANCE.getState();
        cancelButton.setMessage(Text.literal(st.isCaptureSavePending() ? "鍙栨秷淇濆瓨" : "閲嶇疆"));
        cancelButton.active = !st.isCaptureSavePending() || com.formacraft.client.component.ClientComponentImport.canCancelCapture();
        cancelButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        if (st.isCaptureSavePending() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(st)) {
            saveButton.active = false;
            saveButton.setMessage(Text.literal(st.isCaptureSavePending() ? "淇濆瓨涓€? : "璇峰厛閫夋嫨绫诲瀷"));
        } else {
            var healthResult = healthCoordinator.check();
            ComponentCaptureHealthDrawer.configureSaveButton(saveButton, healthResult, canSaveWithoutHealthCheck());
        }

        saveButton.setPosition(x + half + 4, footerTop + 4);
        saveButton.setWidth(w - half - 4);
        saveButton.visible = true;
        saveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
    }

    private void autoCollapseNonActivePhases() {
        currentPhase = computeCurrentPhase();
        if (currentPhase == lastAutoCollapsePhase) {
            return;
        }
        lastAutoCollapsePhase = currentPhase;
        int activeIndex = currentPhase.ordinal();
        for (int i = 0; i < phaseCollapsed.length; i++) {
            phaseCollapsed[i] = i != activeIndex;
        }
    }

    private void typeChanged() {
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        attachmentMode = draft.host.attachment;
        orientationController.stopMarking();
        orientationController.loadFromDraft(draft);
        semanticPreview.invalidate();
        adaptationSection.invalidate();
    }
    
    /**
     * 璁＄畻褰撳墠搴旇澶勪簬鐨勯樁娈?     */
    private CapturePhase computeCurrentPhase() {
        var st = ComponentTool.INSTANCE.getState();
        boolean hasSelection = selectionController.hasValidSelection(client);
        boolean hasAnchor = st.captureDraft.anchor.worldPos != null;
        boolean hasName = st.name != null && !st.name.isBlank() && !st.name.equals("New Component");

        if (!hasSelection) {
            return CapturePhase.SELECTION;
        }
        if (!hasAnchor) {
            return CapturePhase.ANCHOR_ORIENTATION;
        }
        if (!hasName) {
            return CapturePhase.SEMANTIC;
        }
        return CapturePhase.AI_GUARANTEE;
    }
    
    /**
     * 妫€鏌ラ樁娈垫槸鍚﹀畬鎴?     */
    private boolean isPhaseComplete(CapturePhase phase) {
        var st = ComponentTool.INSTANCE.getState();
        return switch (phase) {
            case SELECTION -> selectionController.hasValidSelection(client);
            case ANCHOR_ORIENTATION -> st.captureDraft.anchor.worldPos != null;
            case SEMANTIC ->
                    com.formacraft.client.tool.ComponentCaptureTypes.ready(st) && st.name != null && !st.name.isBlank();
            case AI_GUARANTEE -> true;
            default -> false;
        };
    }

    private void cycleFacing() {
        var st = ComponentTool.INSTANCE.getState();
        Direction base = st.captureDraft.orientation.facing != null ? st.captureDraft.orientation.facing : Direction.SOUTH;
        st.captureDraft.orientation.facing = base.rotateYClockwise();
        st.syncDraftToState();
    }

    private void cycleMirror() {
        var st = ComponentTool.INSTANCE.getState();
        var values = com.formacraft.common.component.transform.Mirror.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.captureDraft.orientation.mirror) {
                idx = i;
                break;
            }
        }
        st.captureDraft.orientation.mirror = values[(idx + 1) % values.length];
        st.syncDraftToState();
    }

    @Override
    public com.formacraft.common.component.placement.AttachmentType getAttachmentMode() {
        return attachmentMode;
    }

    @Override
    public void setNameInputBounds(int x, int y, int w, int h) {
        nameInputX = x;
        nameInputY = y;
        nameInputW = w;
        nameInputH = h;
        nameInputBoundsValid = true;
    }

    @Override
    public void setTagsInputBounds(int x, int y, int w, int h) {
        tagsInputX = x;
        tagsInputY = y;
        tagsInputW = w;
        tagsInputH = h;
        tagsInputBoundsValid = true;
    }

    @Override
    public void setSocketIdInputBounds(int x, int y, int w, int h) {
        socketIdInputX = x;
        socketIdInputY = y;
        socketIdInputW = w;
        socketIdInputH = h;
        socketIdInputBoundsValid = true;
    }

    @Override
    public void updateTagsFromInput() {
        var st = ComponentTool.INSTANCE.getState();
        String text = tagsInput.getText();
        if (text == null || text.isBlank()) {
            st.tags.clear();
            return;
        }

        st.tags.clear();
        for (String tag : text.split("[,锛宂")) {
            tag = tag.trim();
            if (!tag.isEmpty()) {
                st.tags.add(tag);
            }
        }
    }

    @Override
    public boolean isAdvancedOptionsExpanded() {
        return advancedOptionsExpanded;
    }

    private boolean canSaveWithoutHealthCheck() {
        var st = ComponentTool.INSTANCE.getState();
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(st) || st.isCaptureSavePending()) return false;
        if (!selectionController.hasValidSelection(client)) {
            return false;
        }
        if (st.name == null || st.name.isBlank()) {
            return false;
        }
        if (st.captureDraft.anchor.worldPos == null) {
            return false;
        }
        BlockPos anchor = st.captureDraft.anchor.worldPos;
        return selectionController.isAnchorLocationAllowed(anchor);
    }

    private boolean canSave() {
        return canSaveWithoutHealthCheck() && !healthCoordinator.blocksSave();
    }

    private void saveComponent() {
        if (!canSave()) {
            HudToast.show("淇濆瓨澶辫触锛氳妫€鏌ラ€夊尯鍜屽悕绉?, true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        var health = healthCoordinator.check();
        if (health.hasErrors()) {
            HudToast.show("淇濆瓨澶辫触锛氬瓨鍦ㄩ敊璇」锛堣鍏堜慨澶嶏級", true);
            return;
        }
        if (health.hasWarnings()) {
            HudToast.show("瀛樺湪璀﹀憡锛氫粛鍙繚瀛橈紝浣嗗彲鑳藉奖鍝嶆斁缃晥鏋?);
        }
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client, ComponentTool.INSTANCE.getState().captureDraft);
        if (json == null || json.isBlank()) {
            HudToast.show("淇濆瓨澶辫触锛氳妫€鏌ラ€夊尯", true);
            return;
        }

        ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
        String ruleError = com.formacraft.common.component.variant.CapturedComponentRuleValidator.validate(def);
        if (ruleError != null) {
            HudToast.show("淇濆瓨澶辫触锛? + ruleError, true);
            return;
        }
        var saveOutcome = healthCoordinator.prepareForSave(client, json);
        if (saveOutcome.blocked()) {
            HudToast.show(saveOutcome.blockToast(), true);
            return;
        }
        json = saveOutcome.json();
        def = saveOutcome.definition();
        if (saveOutcome.warnToast() != null) {
            HudToast.show(saveOutcome.warnToast());
        }

        // 鐢熸垚缂╃暐鍥撅紙浣跨敤淇鍚庣殑 def锛屽鏋?def 涓?null 鍒欓噸鏂拌В鏋愶級
        byte[] thumbnailPng = null;
        try {
            if (def == null) {
                def = JsonUtil.fromJson(json, ComponentDefinition.class);
            }
            thumbnailPng = thumbnailService.encodePng(def);
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to generate thumbnail", t);
        }

        // 绛夊緟鏈嶅姟绔?ComponentSaveAck锛沜atalog 浠呭埛鏂扮洰褰曟暟鎹?        String componentName = st.name.trim();
        ComponentTool.INSTANCE.markSavePending(componentName);
        HudToast.show("姝ｅ湪淇濆瓨鏋勪欢銆? + componentName + "銆嶁€?);
        try { FormaCraftClientNetworking.sendSaveComponent(json, thumbnailPng); }
        catch (Exception failedSend) {
            com.formacraft.FormacraftMod.LOGGER.warn("Capture upload failed", failedSend);
            ComponentTool.INSTANCE.onSaveAckFromServer("", componentName, false, "淇濆瓨鏋勪欢澶辫触锛? + failedSend.getMessage());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        var captureState = ComponentTool.INSTANCE.getState();
        if (captureState.isCaptureSavePending()) { blurKeyboardFocus(); clickButton(cancelButton, click); return true; }
        if (typeSection.click(mouseX, mouseY, click)) {
            nameInput.setFocused(false); tagsInput.setFocused(false); socketIdInput.setFocused(false); return true;
        }
        if (clickButton(cancelButton, click)) { blurKeyboardFocus(); return true; }
        if (clickButton(saveButton, click)) { blurKeyboardFocus(); return true; }
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(captureState)) { blurKeyboardFocus(); return true; }
        if (mouseY < getContentY() || mouseY >= panelY + panelHeight - STICKY_FOOTER_HEIGHT - 4) {
            blurKeyboardFocus();
            return true;
        }

        if (adaptationSection.click(click, mouseY)) { blurKeyboardFocus(); return true; }

        // 鍋ュ悍妫€鏌ユ憳瑕佽鐐瑰嚮锛堝睍寮€/鎶樺彔鎶藉眽锛?        if (healthCoordinator.handleSummaryClick(mouseY, getPanelY(), scrollY)) {
            blurKeyboardFocus();
            return true;
        }

        Integer hitPhase = phaseHeaders.hitPhase(mouseX, mouseY);
        if (hitPhase != null) {
            phaseCollapsed[hitPhase] = !phaseCollapsed[hitPhase];
            blurKeyboardFocus();
            return true;
        }
        
        // 閫夋嫨宸ュ叿鎸夐挳鐐瑰嚮
        if (clickButton(boxSelectButton, click)) { blurKeyboardFocus(); return true; }
        if (clickButton(pointSelectButton, click)) { blurKeyboardFocus(); return true; }
        if (clickButton(clearSelectionButton, click)) { blurKeyboardFocus(); return true; }
        
        // 杈撳叆妗嗕紭鍏堬細鐐逛腑鍒欒幏鐒︼紝鐐瑰湪澶栭潰浼氳嚜琛屽け鐒?        if (nameInputBoundsValid) {
            if (nameInput.mouseClicked(mouseX, mouseY, nameInputX, nameInputY, nameInputW, nameInputH)) {
                tagsInput.setFocused(false);
                socketIdInput.setFocused(false);
                typeSection.unfocus();
                return true;
            }
        } else {
            nameInput.setFocused(false);
        }

        if (tagsInputBoundsValid) {
            if (tagsInput.mouseClicked(mouseX, mouseY, tagsInputX, tagsInputY, tagsInputW, tagsInputH)) {
                nameInput.setFocused(false);
                socketIdInput.setFocused(false);
                typeSection.unfocus();
                return true;
            }
        } else {
            tagsInput.setFocused(false);
        }

        if (socketIdInputBoundsValid) {
            if (socketIdInput.mouseClicked(mouseX, mouseY, socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH)) {
                nameInput.setFocused(false);
                tagsInput.setFocused(false);
                typeSection.unfocus();
                return true;
            }
        } else {
            socketIdInput.setFocused(false);
        }

        // Phase 3: 璇箟閰嶇疆鎸夐挳鐐瑰嚮
        if (clickButton(attachmentModeButton, click)) return true;
        if (clickButton(directionalityButton, click)) return true;
        if (clickButton(setInsideButton, click)) return true;
        if (clickButton(setOutsideButton, click)) return true;
        if (clickButton(setBottomButton, click)) return true;
        if (clickButton(setTopButton, click)) return true;
        
        // 鎸夐挳鐐瑰嚮
        if (clickButton(categoryButton, click)) return true;
        if (clickButton(advancedOptionsButton, click)) return true;
        if (clickButton(pickAnchorButton, click)) return true;
        if (clickButton(clearAnchorButton, click)) return true;
        if (clickButton(hostFaceButton, click)) return true;
        if (clickButton(anchorOutsideButton, click)) return true;
        if (clickButton(autoAnchorButton, click)) return true;
        if (clickButton(facingButton, click)) return true;
        if (clickButton(mirrorButton, click)) return true;
        
        if (clickButton(semanticSkinButton, click)) return true;
        if (clickButton(semanticTagOnSaveButton, click)) return true;
        if (clickButton(semanticStyleButton, click)) return true;
        if (clickButton(semanticPartButton, click)) return true;
        
        if (clickButton(socketContextButton, click)) return true;
        if (clickButton(socketPickOriginButton, click)) return true;
        if (clickButton(socketFacingButton, click)) return true;
        if (clickButton(socketAddButton, click)) return true;
        if (clickButton(socketPreviewButton, click)) return true;
        if (clickButton(socketClearButton, click)) return true;

        if (clickButton(autoFixButton, click)) return true;
        if (clickButton(undoAutoFixButton, click)) return true;
        
        if (clickButton(cancelButton, click)) return true;
        clickButton(saveButton, click);

        return true; // 娑堣垂鎵€鏈夐潰鏉垮唴鐐瑰嚮
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return;
        
        // 婊氬姩姝ラ暱锛氭瘡娆℃粴杞粴鍔?12 鍍忕礌锛堜笌 ToolPanel 鍜?SettingsPanel 涓€鑷达級
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        
        // 闄愬埗婊氬姩鑼冨洿
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        if (ComponentTool.INSTANCE.getState().isCaptureSavePending()) return;
        typeSection.keyPressed(keyCode, modifiers);
        if (typeSection.focused() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(ComponentTool.INSTANCE.getState())) return;
        nameInput.keyPressed(keyCode, modifiers);
        tagsInput.keyPressed(keyCode, modifiers);
        socketIdInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        if (ComponentTool.INSTANCE.getState().isCaptureSavePending()) return;
        typeSection.charTyped(chr);
        if (typeSection.focused() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(ComponentTool.INSTANCE.getState())) return;
        ensureWidgets();
        nameInput.charTyped(chr);
        tagsInput.charTyped(chr);
        socketIdInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return typeSection.focused() || nameInput.isFocused() || tagsInput.isFocused() || socketIdInput.isFocused();
    }

    @Override
    public void blurKeyboardFocus() {
        typeSection.unfocus();
        nameInput.setFocused(false);
        tagsInput.setFocused(false);
        socketIdInput.setFocused(false);
    }

    private int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = client.textRenderer.fontHeight;
        for (var line : client.textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }

    private int getScaledMouseX() {
        return (int) (client.mouse.getX() / client.getWindow().getScaleFactor());
    }

    private int getScaledMouseY() {
        return (int) (client.mouse.getY() / client.getWindow().getScaleFactor());
    }
    
    // ============ 涓栫晫浜や簰鏂规硶 ============
    
    /**
     * 澶勭悊涓栫晫鐐瑰嚮锛堜粠 InputRouter 璋冪敤锛?     * @return true 濡傛灉浜嬩欢琚鐞?     */
    public boolean handleWorldClick(BlockHitResult hit, int button) {
        var st = ComponentTool.INSTANCE.getState();
        if (st.isCaptureSavePending() || !com.formacraft.client.tool.ComponentCaptureTypes.ready(st)) return true;
        typeSection.unfocus();
        if (hit == null) return false;
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return false;

        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 涓栫晫鐐瑰嚮: {}, 鎸夐挳: {}, 妯″紡: {}, 鏍囪妯″紡: {}",
                pos, button, selectionController.getMode(), orientationController.getMarkingMode());
        }

        // Phase 3: 浼樺厛澶勭悊鏂瑰悜鏍囪妯″紡
        if (orientationController.isMarkingActive() && button == 0) {
            return orientationController.handleMarkingClick(hit, selectionController::isAnchorLocationAllowed);
        }

        // 鍙抽敭璁剧疆閿氱偣
        if (button == 1) {
            setAnchor(pos);
            return true;
        }

        return selectionController.handleWorldClick(client, hit, button);
    }
    
    /**
     * 澶勭悊榧犳爣閲婃斁锛堜粠 InputRouter 璋冪敤锛?     */
    public void handleWorldRelease(int button) {
        selectionController.handleWorldRelease(client, button);
    }

    /**
     * 璁剧疆閿氱偣
     */
    private void setAnchor(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        
        var st = ComponentTool.INSTANCE.getState();
        if (!selectionController.isAnchorLocationAllowed(pos)) {
            HudToast.show("閿氱偣闇€钀藉湪閫夊尯鍐呮垨绱ч偦閫夊尯", true);
            return;
        }
        var draft = st.captureDraft;
        draft.anchor.worldPos = pos.toImmutable();
        st.syncDraftToState();
        st.pickingAnchor = false;
        
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 璁剧疆閿氱偣: {}", pos);
        }
        com.formacraft.client.ui.toast.HudToast.show("閿氱偣宸茶缃? " + pos.toShortString());
    }

    private void setAutoAnchor() {
        if (!selectionController.hasValidSelection()) {
            HudToast.show("璇峰厛閫夋嫨鏋勪欢鏂瑰潡", true);
            return;
        }
        BlockPos min = selectionController.getSelectionMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            HudToast.show("閫夊尯鏃犳晥锛屾棤娉曡嚜鍔ㄨ缃敋鐐?, true);
            return;
        }
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        BlockPos anchor = new BlockPos(cx, min.getY(), cz);
        setAnchor(anchor);
    }
    
    public void clearSelection() {
        selectionController.clearSelection();
        thumbnailService.invalidate();
        orientationController.clearMarks();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 娓呴櫎閫夊尯");
        }
    }

    public void tick() {
        var st = ComponentTool.INSTANCE.getState();
        if (st.isCaptureSavePending()) return;
        boolean wasActive = st.captureActive;
        st.beginCapture();
        if (!wasActive && st.captureActive) {
            syncLocalFromState();
        }
        st.syncDraftToState();
        selectionController.tick(client);
        attachmentMode = st.captureDraft.host.attachment;
        orientationController.loadFromDraft(st.captureDraft);
    }

    public void renderWorldSelection(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        worldOverlay.render(ctx);
    }

    /**
     * 灏嗗綋鍓?state 鍚屾鍒伴潰鏉挎湰鍦扮紦瀛橈紙鐢ㄤ簬杩涘叆鎹曡幏鎬佹椂鍥炲～锛夈€?     */
    private void syncLocalFromState() {
        var st = ComponentTool.INSTANCE.getState();
        selectionController.loadFromDraft(st.captureDraft, client);
        orientationController.loadFromDraft(st.captureDraft);
        if (!st.captureDraft.selection.explicit) {
            st.captureDraft.selection.aabbMin = SelectionTool.INSTANCE.getMin();
            st.captureDraft.selection.aabbMax = SelectionTool.INSTANCE.getMax();
        }
        attachmentMode = st.captureDraft.host.attachment;
    }
}
