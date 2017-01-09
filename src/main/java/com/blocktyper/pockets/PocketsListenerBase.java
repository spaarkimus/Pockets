package com.blocktyper.pockets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.blocktyper.recipes.BlockTyperRecipe;
import com.blocktyper.recipes.IRecipe;
import com.blocktyper.serialization.CardboardBox;

public abstract class PocketsListenerBase implements Listener {

	protected PocketsPlugin plugin;
	private Map<String, ItemStack> openPocketMap = new HashMap<>();

	protected static final int INVENTORY_COLUMNS = 9;
	protected static final Material BLACKOUT_MATERIAL = Material.STAINED_GLASS_PANE;
	protected static final String BLACKOUT_TEXT = "---";
	public static final String POCKETS_HIDDEN_LORE_KEY = "#PKT";

	public PocketsListenerBase(PocketsPlugin plugin) {
		this.plugin = plugin;
		this.plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void setPocketJson(ItemStack itemWithPocket, List<ItemStack> itemsInPocket, HumanEntity player, boolean includePrefix) {

		if (itemWithPocket == null) {
			return;
		}

		List<CardboardBox> contents = null;

		if (itemsInPocket != null && !itemsInPocket.isEmpty()) {
			contents = itemsInPocket.stream().filter(i -> i != null).map(i -> new CardboardBox(i))
					.collect(Collectors.toList());
		} else {
			contents = new ArrayList<>();
		}

		Pocket pocket = new Pocket();
		pocket.setContents(contents);

		int itemCount = contents != null ? contents.size() : 0;
		
		IRecipe recipe = plugin.recipeRegistrar().getRecipeFromKey(PocketsPlugin.POCKET_RECIPE_KEY);
		String pocketName = plugin.recipeRegistrar().getNameConsiderLocalization(recipe, player);

		String visiblePrefix = includePrefix ? pocketName + " [" + itemCount + "]" : null;

		plugin.getInvisibleLoreHelper().setInvisisbleJson(pocket, itemWithPocket, POCKETS_HIDDEN_LORE_KEY, visiblePrefix);
	}

	protected String getMaterialSettingConfigKey(Material material, String suffix) {
		return ConfigKeyEnum.MATERIAL_SETTINGS.getKey() + "." + material.name() + "." + suffix;
	}

	protected boolean isUserPermitted(HumanEntity player, boolean isGeneral, boolean sendMessage) {

		boolean userHasPermission = true;

		ConfigKeyEnum requirePermissionKey = isGeneral ? ConfigKeyEnum.REQUIRE_PERMISSIONS_FOR_GENERAL_USE
				: ConfigKeyEnum.REQUIRE_PERMISSIONS_FOR_POCKET_IN_POCKET_USE;

		boolean requireUsePermissions = plugin.getConfig().getBoolean(requirePermissionKey.getKey(), false);

		if (requireUsePermissions) {
			String requiredTypeString = (isGeneral ? "General use" : "Pocket in pocket") + " permissions required";
			plugin.debugInfo(requiredTypeString);
			userHasPermission = false;

			ConfigKeyEnum permissionsKey = isGeneral ? ConfigKeyEnum.GENERAL_USE_PERMISSIONS
					: ConfigKeyEnum.POCKET_IN_POCKET_USE_USE_PERMISSIONS;
			String usePermissions = plugin.getConfig().getString(permissionsKey.getKey(), null);

			if (usePermissions == null) {
				plugin.warning(requiredTypeString + ", but no permission supplied.  Please set the value for the ["
						+ permissionsKey + "] configuration key.");
			} else {
				plugin.debugInfo("Checking use permissions: " + usePermissions);
				userHasPermission = plugin.getPlayerHelper().playerCanDoAction(player, Arrays.asList(usePermissions));
				plugin.debugInfo(userHasPermission ? "Permission granted" : "Permission denied");
			}
		}

		if (!userHasPermission && sendMessage) {
			String message = plugin.getLocalizedMessage(LocalizedMessageEnum.PERMISSION_DENIED.getKey(), player);
			player.sendMessage(message);
		}

		return userHasPermission;
	}

	protected void openInventory(ItemStack clickedItem, List<ItemStack> contents, Player player, Cancellable event) {

		if (!isUserPermitted(player, true, true)) {
			return;
		}

		int pocketSizeLimit = plugin.getConfig().getInt(
				getMaterialSettingConfigKey(clickedItem.getType(), ConfigKeyEnum.MATERIAL_SETTING_LIMIT.getKey()));

		plugin.debugInfo("pocketSizeLimit [initial]: " + pocketSizeLimit);
		if (pocketSizeLimit <= 0) {
			pocketSizeLimit = plugin.getConfig().getInt(ConfigKeyEnum.DEFAULT_POCKET_SIZE_LIMIT.getKey(), 6);
			plugin.debugInfo("pocketSizeLimit [secondary]: " + pocketSizeLimit);
		}
		
		if(contents == null)
			contents = new ArrayList<>();

		if (contents.size() > pocketSizeLimit)
			pocketSizeLimit = contents.size();

		plugin.debugInfo("pocketSizeLimit [final]: " + pocketSizeLimit);

		int rows = (pocketSizeLimit / INVENTORY_COLUMNS) + (pocketSizeLimit % INVENTORY_COLUMNS > 0 ? 1 : 0);
		
		IRecipe recipe = plugin.recipeRegistrar().getRecipeFromKey(PocketsPlugin.POCKET_RECIPE_KEY);
		String pocketName = plugin.recipeRegistrar().getNameConsiderLocalization(recipe, player);

		Inventory pocketsInventory = Bukkit.createInventory(null, rows * INVENTORY_COLUMNS, pocketName);

		int i = -1;
		boolean noPocketInPocketIssueLocated = true;
		boolean noForeignInvisIssueFound = true;

		for (ItemStack item : contents) {
			if (item == null || item.getType().equals(Material.AIR))
				continue;

			if (foreignInvisIssue(clickedItem, player, noForeignInvisIssueFound)) {
				noForeignInvisIssueFound = false;
				tryToFitItemInPlayerInventory(item, player);
				continue;
			}else if (pocketInPocketIssue(clickedItem, item, player, noPocketInPocketIssueLocated)) {
				noPocketInPocketIssueLocated = false;
				tryToFitItemInPlayerInventory(item, player);
				continue;
			}

			i++;
			pocketsInventory.setItem(i, item);
		}

		int availableSlotsOnLastRow = pocketSizeLimit >= INVENTORY_COLUMNS ? pocketSizeLimit % INVENTORY_COLUMNS
				: pocketSizeLimit;

		plugin.debugInfo("availableSlotsOnLastRow: " + availableSlotsOnLastRow);

		if (availableSlotsOnLastRow > 0) {
			// we must add black pane glass slots to the end of the inventory
			// which cannot be moved
			int blackedOutSlotsRequired = INVENTORY_COLUMNS - availableSlotsOnLastRow;

			plugin.debugInfo("blackedOutSlotsRequired: " + blackedOutSlotsRequired);
			for (i = pocketSizeLimit; i < pocketSizeLimit + blackedOutSlotsRequired; i++) {
				ItemStack blackOut = new ItemStack(BLACKOUT_MATERIAL);
				ItemMeta itemMeta = blackOut.getItemMeta();
				itemMeta.setDisplayName(BLACKOUT_TEXT);
				itemMeta.setLore(new ArrayList<>());
				itemMeta.getLore().add(plugin.getInvisibleLoreHelper().convertToInvisibleString(i + ""));
				blackOut.setItemMeta(itemMeta);
				pocketsInventory.setItem(i, blackOut);
			}
		}

		if (event != null)
			event.setCancelled(true);

		if (openPocketMap == null)
			openPocketMap = new HashMap<>();

		openPocketMap.put(player.getName(), clickedItem);

		if (!noPocketInPocketIssueLocated || !noForeignInvisIssueFound) {
			// if we had to change the contents of the inventory because had
			// invalid pocket-in-pocket items
			// then we need to re-save the item data before opening it.
			saveInventoryIntoItem(player, pocketsInventory, true);
		}

		PocketDelayOpener pocketDelayOpener = new PocketDelayOpener(plugin, player, pocketsInventory);
		pocketDelayOpener.runTaskLater(plugin, 5L * 1);
	}

	protected void saveInventoryIntoItem(HumanEntity player, Inventory inventory) {
		saveInventoryIntoItem(player, inventory, false);
	}

	protected void saveInventoryIntoItem(HumanEntity player, Inventory inventory, boolean isOnClose) {
		ItemStack itemWithPocket = getActivePocketItem(player);

		if (itemWithPocket == null) {
			plugin.debugInfo("itemWithPocket == null");
			return;
		}

		ItemStack[] items = inventory.getStorageContents();

		List<ItemStack> itemsInPocketTemp = items == null ? null
				: Arrays.asList(items).stream().filter(i -> !isBlackoutItem(i)).collect(Collectors.toList());

		List<ItemStack> itemsInPocket = null;
		boolean showPocketInPocketWarning = true;
		boolean showForeignInvisIssue = true;
		if (itemsInPocketTemp != null) {
			itemsInPocket = new ArrayList<>();
			for (ItemStack item : itemsInPocketTemp) {
				if(item != null){
					if (foreignInvisIssue(item, player, showForeignInvisIssue)) {
						showForeignInvisIssue = false;
						tryToFitItemInPlayerInventory(item, player);
						continue;
					}else if (pocketInPocketIssue(itemWithPocket, item, player, showPocketInPocketWarning)) {
						showPocketInPocketWarning = false;
						tryToFitItemInPlayerInventory(item, player);
						continue;
					}
					itemsInPocket.add(item);
				}
				
			}
		}

		if (isOnClose) {
			plugin.debugInfo("SAVING on inventory close");
			setPocketJson(itemWithPocket, itemsInPocket, player, true);
		} else {
			plugin.debugInfo("SAVING after inventory action");
			setPocketJson(itemWithPocket, itemsInPocket, player, true);
		}
	}

	protected boolean isBlackoutItem(ItemStack item) {
		if (item == null)
			return false;
		if (!item.getType().equals(BLACKOUT_MATERIAL))
			return false;
		if (item.getItemMeta() == null || item.getItemMeta().getDisplayName() == null)
			return false;
		if (!item.getItemMeta().getDisplayName().equals(BLACKOUT_TEXT))
			return false;

		return true;
	}

	protected void tryToFitItemInPlayerInventory(ItemStack item, HumanEntity player) {
		HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(item);
		plugin.debugWarning("tryToFitItemInPlayerInventory: " + item.getType() + "[" + item.getAmount() + "]");
		if (remaining == null || remaining.values() == null || remaining.values().isEmpty()) {
			remaining.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
		}
	}

	protected boolean pocketInPocketIssue(ItemStack itemWithPocket, ItemStack itemInPocket, HumanEntity player) {
		return pocketInPocketIssue(itemWithPocket, itemInPocket, player, true);
	}

	protected boolean pocketInPocketIssue(ItemStack itemWithPocket, ItemStack itemInPocket, HumanEntity player,
			boolean showWarning) {
		boolean defaultAllowPocketsInPocket = plugin.getConfig().getBoolean(getMaterialSettingConfigKey(
				itemWithPocket.getType(), ConfigKeyEnum.DEFAULT_ALLOW_POCKET_IN_POCKET.getKey()), true);
		boolean allowPocketsInPocket = plugin.getConfig()
				.getBoolean(
						getMaterialSettingConfigKey(itemWithPocket.getType(),
								ConfigKeyEnum.MATERIAL_SETTING_ALLOW_POCKET_IN_POCKET.getKey()),
						defaultAllowPocketsInPocket);

		Pocket pocket = getPocket(itemInPocket, player);
		if (pocket != null && pocket.getContents() != null && !pocket.getContents().isEmpty()) {
			if (!allowPocketsInPocket) {
				if (showWarning) {
					String message = plugin
							.getLocalizedMessage(LocalizedMessageEnum.POCKETS_IN_POCKETS_NOT_ALLOWED.getKey(), player);
					player.sendMessage(ChatColor.RED + message);
				}
				return true;
			} else if (!isUserPermitted(player, false, showWarning)) {
				return true;
			}
		}

		return false;
	}
	
	protected boolean foreignInvisIssue(ItemStack itemInPocket, HumanEntity player) {
		return foreignInvisIssue(itemInPocket, player, true);
	}
	
	protected boolean foreignInvisIssue(ItemStack itemInPocket, HumanEntity player,
			boolean showWarning) {
		boolean foreignInvisExists = false;
		
		plugin.debugInfo("$$$$$$$$$$$$$$$$ Checking foreignInvisExists: " + (itemInPocket != null ? itemInPocket.getType().name() : "null"));
		
		if(itemInPocket == null || itemInPocket.getItemMeta() == null){
			foreignInvisExists = false;
		}else if(itemInPocket.getItemMeta().getDisplayName() != null && itemInPocket.getItemMeta().getDisplayName().contains(ChatColor.COLOR_CHAR+"")){
			foreignInvisExists = true;
		}else if(itemInPocket.getItemMeta().getLore() == null || itemInPocket.getItemMeta().getLore().isEmpty()){
			foreignInvisExists = false;
		}else{
			foreignInvisExists = itemInPocket.getItemMeta().getLore().stream().anyMatch(l -> foreignInvisIssue(l));
		}
		
		if (foreignInvisExists && showWarning) {
			String message = plugin
					.getLocalizedMessage(LocalizedMessageEnum.FOREIGN_INVIS_NOT_ALLOWED.getKey(), player);
			player.sendMessage(ChatColor.RED + message);
		}
		
		plugin.debugInfo("$$$$$$$$$$$$$$$$$$ No foreignInvisExists");

		return foreignInvisExists;
	}
	
	private boolean foreignInvisIssue(String loreLine){
		boolean foreignInvisExists = false;
		
		if(loreLine == null || loreLine.isEmpty() || !loreLine.contains(ChatColor.COLOR_CHAR+"")){
			foreignInvisExists = false;
		}else{
			String visibleLine = plugin.getInvisibleLoreHelper().convertToVisibleString(loreLine);
			foreignInvisExists = !visibleLine.contains(POCKETS_HIDDEN_LORE_KEY) && !BlockTyperRecipe.isHiddenRecipeKey(visibleLine);
		}
		return foreignInvisExists;
	}

	protected Pocket getPocket(ItemStack item, HumanEntity player) {
		return getPocket(item, true, player);
	}

	protected Pocket getPocket(ItemStack item, boolean hideChildPockets, HumanEntity player) {

		Pocket pocket = plugin.getInvisibleLoreHelper().getObjectFromInvisisibleLore(item, POCKETS_HIDDEN_LORE_KEY, Pocket.class);

		if (pocket != null && pocket.getContents() != null && !pocket.getContents().isEmpty()) {			
			List<CardboardBox> newContents = new ArrayList<>();
			for(CardboardBox box : pocket.getContents()){
				ItemStack unboxedItem = box != null ? (hideChildPockets ? getPocketItemsHidden(box, player) : box.unbox()) : null;
				if(unboxedItem != null){
					
					if(unboxedItem.getItemMeta() != null && unboxedItem.getItemMeta().getLore() != null){
						List<String> lore = new ArrayList<>();
						for(String loreLine : unboxedItem.getItemMeta().getLore()){
							if(BlockTyperRecipe.isHiddenRecipeKey(loreLine)){
								lore.add(plugin.getInvisibleLoreHelper().convertToInvisibleString(loreLine));
							}else{
								lore.add(loreLine);
							}
						}

						ItemMeta itemMeta = unboxedItem.getItemMeta();
						itemMeta.setLore(lore);
						unboxedItem.setItemMeta(itemMeta);
					}
					
					CardboardBox newBox = new CardboardBox(unboxedItem);
					newContents.add(newBox);
				}else{
					newContents.add(box);
				}
			}
			pocket.setContents(newContents);
		}

		return pocket;
	}

	protected ItemStack getPocketItemsHidden(CardboardBox box, HumanEntity player) {
		ItemStack item = box != null ? box.unbox() : null;
		if (item != null) {
			Pocket pocket = getPocket(item, false, player);
			if (pocket != null) {
				List<ItemStack> itemsInPocket = pocket.getContents() == null ? null
						: pocket.getContents().stream().map(c -> c.unbox()).collect(Collectors.toList());
				setPocketJson(item, itemsInPocket, player, true);
			}
		}
		return item;
	}

	protected List<ItemStack> getPocketContents(ItemStack item, boolean hideChildPockets, HumanEntity player) {
		return getPocketContents(getPocket(item, hideChildPockets, player));
	}

	protected List<ItemStack> getPocketContents(Pocket pocket) {
		return pocket == null || pocket.getContents() == null ? null
				: pocket.getContents().stream().filter(c -> c != null).map(c -> c.unbox()).collect(Collectors.toList());
	}

	protected ItemStack getActivePocketItem(HumanEntity player) {
		if (openPocketMap == null)
			openPocketMap = new HashMap<>();

		return openPocketMap.containsKey(player.getName()) ? openPocketMap.get(player.getName()) : null;
	}

}
