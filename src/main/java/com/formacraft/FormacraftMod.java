package com.formacraft;

import com.formacraft.common.config.ConfigManager;
import com.formacraft.common.item.FormaCraftToolItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FormacraftMod implements ModInitializer {
	public static final String MOD_ID = "formacraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * 在 1.21.10 中，Item 在构造时会立即从 Settings 中读取 ID，
	 * 如果在创建 Item 之前没有把 ID 写进 Settings，就会出现 "Item id not set" 的崩溃。
	 *
	 * 正确做法：先创建 Item 的 RegistryKey，然后把这个 Key 写进 Settings，
	 * 再用这个 Settings 去构造物品并注册。
	 */
	public static final RegistryKey<Item> FORMACRAFT_TOOL_KEY =
			RegistryKey.of(Registries.ITEM.getKey(), Identifier.of(MOD_ID, "formacraft_tool"));

	public static Item FORMACRAFT_TOOL;

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("FormaCraft initialized!");
		ConfigManager.loadConfig();

		// 先把 ID 写入 Settings，再创建物品，避免 "Item id not set" 崩溃
		Item.Settings toolSettings = new Item.Settings().maxCount(1);
		try {
			// 新版 API 为 Settings 提供了 registryKey，优先尝试使用
			toolSettings = toolSettings.registryKey(FORMACRAFT_TOOL_KEY);
		} catch (NoSuchMethodError | NoClassDefFoundError e) {
			// 兼容性保护：如果当前 mappings / Fabric 版本没有该方法，忽略，
			// 此时仍然可能出现崩溃，但至少不会在编译阶段失败。
			LOGGER.warn("Item.Settings.registryKey not available, FormaCraftToolItem may crash with 'Item id not set' on this mappings/Fabric version.", e);
		}

		FORMACRAFT_TOOL = new FormaCraftToolItem(toolSettings);

		// 使用 RegistryKey 进行注册，确保与 Settings 中的 Key 一致
		Registry.register(Registries.ITEM, FORMACRAFT_TOOL_KEY, FORMACRAFT_TOOL);
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(FORMACRAFT_TOOL));
	}
}