package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;

import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ChatPreviewAdjustCommand(int dx, int dy, int dz, String hint) {
            private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3})");

        public static ChatPreviewAdjustCommand parse(String raw, Direction facing) {
                String text = raw.trim();
                if (text.isEmpty() || text.startsWith("/")) {
                    return null;
                }
                String lower = text.toLowerCase(Locale.ROOT);

                int step = parseStep(lower);
                Direction dir = parseAbsoluteDirection(lower);
                if (dir == null) {
                    dir = parseRelativeDirection(lower, facing);
                }

                int dx = 0;
                int dy = 0;
                int dz = 0;

                if (dir != null) {
                    dx = dir.getOffsetX() * step;
                    dz = dir.getOffsetZ() * step;
                }
                if (containsAny(lower, "上移", "往上", "向上", "上挪", "抬高", "升高", "往高", "向高", "up", "raise")) {
                    dy = step;
                } else if (containsAny(lower, "下移", "往下", "向下", "下挪", "降低", "下降", "down", "lower")) {
                    dy = -step;
                }

                if (dx == 0 && dy == 0 && dz == 0) {
                    return null;
                }
                String hint = Text.translatable("formacraft.chat.preview.adjust.hint", dx, dy, dz).getString();
                return new ChatPreviewAdjustCommand(dx, dy, dz, hint);
            }

            public static int parseStep(String text) {
                Matcher m = NUMBER_PATTERN.matcher(text);
                if (m.find()) {
                    try {
                        int v = Integer.parseInt(m.group(1));
                        return Math.max(1, Math.min(32, v));
                    } catch (NumberFormatException e) {
                        ChatPanel.LOG.debug("parseStep number failed text={}", text);
                    }
                }
                if (text.contains("多一点") || text.contains("再多") || text.contains("再往")) {
                    return 2;
                }
                return 1;
            }

            public static Direction parseAbsoluteDirection(String text) {
                if (containsAny(text, "向北", "往北", "北移", "north")) return Direction.NORTH;
                if (containsAny(text, "向南", "往南", "南移", "south")) return Direction.SOUTH;
                if (containsAny(text, "向东", "往东", "东移", "east")) return Direction.EAST;
                if (containsAny(text, "向西", "往西", "西移", "west")) return Direction.WEST;
                return null;
            }

            public static Direction parseRelativeDirection(String text, Direction facing) {
                if (facing == null) return null;
                if (containsAny(text, "往前", "向前", "前移", "前挪", "forward")) {
                    return facing;
                }
                if (containsAny(text, "往后", "向后", "后移", "后退", "back")) {
                    return facing.getOpposite();
                }
                if (containsAny(text, "往左", "向左", "左移", "left")) {
                    return facing.rotateYCounterclockwise();
                }
                if (containsAny(text, "往右", "向右", "右移", "right")) {
                    return facing.rotateYClockwise();
                }
                return null;
            }

            public static boolean containsAny(String text, String... keys) {
                for (String key : keys) {
                    if (text.contains(key)) return true;
                }
                return false;
            }
        }
