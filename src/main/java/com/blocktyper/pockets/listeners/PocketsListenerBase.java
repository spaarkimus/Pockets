package com.blocktyper.pockets.listeners;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.blocktyper.v1_2_6.helpers.InvisHelper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import com.blocktyper.pockets.ConfigKeyEnum;
import com.blocktyper.pockets.LocalizedMessageEnum;
import com.blocktyper.pockets.PocketsPlugin;
import com.blocktyper.pockets.data.Pocket;
import com.blocktyper.pockets.utils.OldPocketHelper;
import com.blocktyper.v1_2_6.BlockTyperListener;
import com.blocktyper.v1_2_6.helpers.ComplexMaterial;
import com.blocktyper.v1_2_6.helpers.InvisHelper;
import com.blocktyper.v1_2_6.nbt.NBTItem;
import com.blocktyper.v1_2_6.recipes.AbstractBlockTyperRecipe;
import com.blocktyper.v1_2_6.recipes.IRecipe;
import com.blocktyper.v1_2_6.serialization.CardboardBox;

public abstract class PocketsListenerBase extends BlockTyperListener {

	protected PocketsPlugin plugin;
	private Map<String, ItemStack> openPocketMap = new HashMap<>();
	private Map<String, Inventory> activeInventoryMap = new HashMap<>();

	protected static final int INVENTORY_COLUMNS = 9;
	protected static final Material BLACKOUT_MATERIAL = Material.STAINED_GLASS_PANE;
	protected static final String BLACKOUT_TEXT = "---";
	public static final String POCKETS_HIDDEN_LORE_KEY = "#PKT";
	public static final String YOUR_POCKETS_HIDDEN_LORE_KEY = "#YOUR_PKTS";

	public static final String POCKETS_SIZE_HIDDEN_LORE_KEY = "#SIZE_PRT";

	public static final String POCKET_NBT_JSON_KEY = "pocket.json";
	
	public static String MATERIAL_CONFIG_KEY_FORMAT = ConfigKeyEnum.MATERIAL_SETTINGS.getKey() + ".{0}.{1}";

	private OldPocketHelper oldPocketHelper;

	public static Set<Material> INCOMPATIBLE_MATERIALS;
	static {
		INCOMPATIBLE_MATERIALS = new HashSet<>();
		INCOMPATIBLE_MATERIALS.add(Material.BOOK_AND_QUILL);
		INCOMPATIBLE_MATERIALS.add(Material.WRITTEN_BOOK);
		INCOMPATIBLE_MATERIALS.add(Material.ENCHANTED_BOOK);
	}

	public PocketsListenerBase(PocketsPlugin plugin) {
		this(plugin, false);
	}

	public PocketsListenerBase(PocketsPlugin plugin, boolean isOldHelper) {
		init(plugin);
		this.plugin = plugin;
		if (!isOldHelper) {
			this.plugin.getServer().getPluginManager().registerEvents(this, plugin);
			oldPocketHelper = new OldPocketHelper(plugin);
		}
	}

	///////////////////////
	// AUTH//////////
	///////////////////////

	/**
	 * 
	 * @param player
	 * @param isGeneral
	 * @param sendMessage
	 * @return
	 */
	protected boolean isUserPermitted(HumanEntity player, boolean isGeneral, boolean sendMessage) {

		boolean userHasPermission = true;

		ConfigKeyEnum requirePermissionKey = isGeneral ? ConfigKeyEnum.REQUIRE_PERMISSIONS_FOR_GENERAL_USE
				: ConfigKeyEnum.REQUIRE_PERMISSIONS_FOR_POCKET_IN_POCKET_USE;

		boolean requireUsePermissions = plugin.getConfig().getBoolean(requirePermissionKey.getKey(), false);

		if (requireUsePermissions) {
			String requiredTypeString = (isGeneral ? "General use" : "Pocket in pocket") + " permissions required";
			debugInfo(requiredTypeString);
			userHasPermission = false;

			ConfigKeyEnum permissionsKey = isGeneral ? ConfigKeyEnum.GENERAL_USE_PERMISSIONS
					: ConfigKeyEnum.POCKET_IN_POCKET_USE_USE_PERMISSIONS;
			String usePermissions = plugin.getConfig().getString(permissionsKey.getKey(), null);

			if (usePermissions == null) {
				warning(requiredTypeString + ", but no permission supplied.  Please set the value for the ["
						+ permissionsKey + "] configuration key.");
			} else {
				debugInfo("Checking use permissions: " + usePermissions);
				userHasPermission = getPlayerHelper().playerCanDoAction(player, Arrays.asList(usePermissions));
				debugInfo(userHasPermission ? "Permission granted" : "Permission denied");
			}
		}

		if (!userHasPermission && sendMessage) {
			String message = getLocalizedMessage(LocalizedMessageEnum.PERMISSION_DENIED.getKey(), player);
			player.sendMessage(message);
		}

		return userHasPermission;
	}

	/**
	 * 
	 * @param itemInPocket
	 * @param player
	 * @param sendMessage
	 * @return
	 */
	protected boolean incompatibleIssue(ItemStack itemInPocket, HumanEntity player, boolean sendMessage) {
		if (itemInPocket != null && INCOMPATIBLE_MATERIALS.contains(itemInPocket.getType())) {
			if (sendMessage) {
				String message = getLocalizedMessage(LocalizedMessageEnum.OBJECT_NOT_COMPATIBLE.getKey(),
						player);
				player.sendMessage(ChatColor.RED + message);
			}
			return true;
		}
		return false;
	}

	/**
	 * 
	 * @param itemWithPocket
	 * @param itemInPocket
	 * @param player
	 * @return
	 */
	protected boolean notAllowedIssue(ItemStack itemWithPocket, ItemStack itemInPocket, HumanEntity player) {
		return notAllowedIssue(itemWithPocket, itemInPocket, player, true);
	}

	/**
	 * 
	 * @param itemWithPocket
	 * @param itemInPocket
	 * @param player
	 * @param showWarning
	 * @return
	 */
	protected boolean notAllowedIssue(ItemStack itemWithPocket, ItemStack itemInPocket, HumanEntity player,
			boolean showWarning) {
		
		boolean defaultAllowPocketsInPocket = plugin.getConfig().getBoolean(ConfigKeyEnum.DEFAULT_ALLOW_POCKET_IN_POCKET.getKey());
		
		Boolean allowPocketsInPocket = plugin.getConfig().getBoolean(
				getMaterialSettingConfigKey(itemWithPocket,
						ConfigKeyEnum.MATERIAL_SETTING_ALLOW_POCKET_IN_POCKET.getKey()),
				defaultAllowPocketsInPocket);

		Pocket pocket = getPocket(itemInPocket);
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
		
		String itemWhitelistKey = getMaterialSettingConfigKey(itemWithPocket, ConfigKeyEnum.WHITELIST.getKey());
		List<String> whiteListedItems = getConfig().getStringList(itemWhitelistKey);
		
		ComplexMaterial itemInPocketMaterial = new ComplexMaterial(itemInPocket);
		boolean inItemWhiteList = false;
		
		if(whiteListedItems != null && !whiteListedItems.isEmpty()){
			inItemWhiteList = whiteListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT)) || whiteListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT_HIDE_ZERO));
			if(!inItemWhiteList){
				if (showWarning) {
					String message = plugin
							.getLocalizedMessage(LocalizedMessageEnum.ITEM_WHITELIST_ISSUE.getKey(), player);
					player.sendMessage(ChatColor.RED + message);
				}
				return true;
			}
		}
		
		if(!inItemWhiteList){
			String itemBlackListKey = getMaterialSettingConfigKey(itemWithPocket, ConfigKeyEnum.BLACKLIST.getKey());
			List<String> blackListedItems = getConfig().getStringList(itemBlackListKey);
			
			if(blackListedItems != null && !blackListedItems.isEmpty()){
				boolean inItemBlackList = blackListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT)) || blackListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT_HIDE_ZERO));
				
				if(inItemBlackList){
					if (showWarning) {
						String message = plugin
								.getLocalizedMessage(LocalizedMessageEnum.ITEM_BLACKLIST_ISSUE.getKey(), player);
						player.sendMessage(ChatColor.RED + message);
					}
					return true;
				}
			}
			
			blackListedItems = getConfig().getStringList(ConfigKeyEnum.BLACKLIST.getKey());
			if(blackListedItems != null && !blackListedItems.isEmpty()){
				boolean inItemBlackList = blackListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT)) || blackListedItems.contains(itemInPocketMaterial.toString(ComplexMaterial.TO_MAT_HIDE_ZERO));
				
				if(inItemBlackList){
					if (showWarning) {
						String message = plugin
								.getLocalizedMessage(LocalizedMessageEnum.GLOBAL_BLACKLIST_ISSUE.getKey(), player);
						player.sendMessage(ChatColor.RED + message);
					}
					return true;
				}
			}
		}
		
		return false;
	}

	///////////////////////
	// INVENTORY//////////
	///////////////////////

	/**
	 * 
	 * @param clickedItem
	 * @param contents
	 * @param player
	 * @return
	 */
	protected Inventory getPocketInventory(ItemStack clickedItem, List<ItemStack> contents, Player player) {

		int pocketSizeLimit = plugin.getConfig().getInt(
				getMaterialSettingConfigKey(clickedItem, ConfigKeyEnum.MATERIAL_SETTING_LIMIT.getKey()));
		
		debugInfo("pocketSizeLimit [initial]: " + pocketSizeLimit);
		if (pocketSizeLimit <= 0) {
			pocketSizeLimit = plugin.getConfig().getInt(ConfigKeyEnum.DEFAULT_POCKET_SIZE_LIMIT.getKey(), 6);
			debugInfo("pocketSizeLimit [secondary]: " + pocketSizeLimit);
		}

		if (contents == null)
			contents = new ArrayList<>();

		if (contents.size() > pocketSizeLimit)
			pocketSizeLimit = contents.size();

		debugInfo("pocketSizeLimit [final]: " + pocketSizeLimit);

		int rows = (pocketSizeLimit / INVENTORY_COLUMNS) + (pocketSizeLimit % INVENTORY_COLUMNS > 0 ? 1 : 0);

		IRecipe recipe = recipeRegistrar().getRecipeFromKey(PocketsPlugin.POCKET_RECIPE_KEY);
		String pocketName = recipeRegistrar().getNameConsiderLocalization(recipe, player);

		Inventory pocketsInventory = Bukkit.createInventory(null, rows * INVENTORY_COLUMNS, pocketName);

		int i = -1;
		boolean noPocketInPocketIssueLocated = true;
		boolean noIncompatibleIssue = true;

		for (ItemStack item : contents) {
			boolean itemCanGoInPocket = true;
			if (item == null || item.getType().equals(Material.AIR))
				continue;

			if (notAllowedIssue(clickedItem, item, player, noPocketInPocketIssueLocated)) {
				noPocketInPocketIssueLocated = itemCanGoInPocket = false;
			} else if (incompatibleIssue(item, player, noIncompatibleIssue)) {
				noIncompatibleIssue = itemCanGoInPocket = false;
			}

			if (!itemCanGoInPocket) {
				tryToFitItemInPlayerInventory(item, player);
				continue;
			}

			i++;
			pocketsInventory.setItem(i, item);
		}

		int availableSlotsOnLastRow = pocketSizeLimit >= INVENTORY_COLUMNS ? pocketSizeLimit % INVENTORY_COLUMNS
				: pocketSizeLimit;

		debugInfo("availableSlotsOnLastRow: " + availableSlotsOnLastRow);

		if (availableSlotsOnLastRow > 0) {
			// we must add black pane glass slots to the end of the inventory
			// which cannot be moved
			int blackedOutSlotsRequired = INVENTORY_COLUMNS - availableSlotsOnLastRow;

			debugInfo("blackedOutSlotsRequired: " + blackedOutSlotsRequired);
			fillWithBlackOutItems(pocketsInventory, pocketSizeLimit, pocketSizeLimit, blackedOutSlotsRequired);
		}

		if (!noPocketInPocketIssueLocated || !noIncompatibleIssue) {
			// if we had to change the contents of the inventory because had
			// invalid pocket-in-pocket items
			// then we need to re-save the item data before opening it.
			saveInventoryIntoItem(player, pocketsInventory, true);
		}

		return pocketsInventory;
	}

	/**
	 * 
	 * @param clickedItem
	 * @param contents
	 * @param player
	 */
	protected void openInventory(ItemStack clickedItem, List<ItemStack> contents, Player player) {

		if (!isUserPermitted(player, true, true)) {
			return;
		}

		Inventory pocketsInventory = getPocketInventory(clickedItem, contents, player);

		// PocketDelayOpener pocketDelayOpener = new PocketDelayOpener(plugin,
		// player, pocketsInventory);
		// pocketDelayOpener.runTaskLater(plugin, 5L * 1);

		new BukkitRunnable() {
			@Override
			public void run() {
				player.closeInventory();
				PocketsListenerBase.AddPlayerWithPocketInventoryOpen(player, pocketsInventory);
				player.openInventory(pocketsInventory);

			}
		}.runTaskLater(plugin, 1L);
	}

	/**
	 * 
	 * @param inventory
	 * @param startingIndex
	 * @param sizeLimit
	 * @param slotsRequred
	 */
	protected void fillWithBlackOutItems(Inventory inventory, int startingIndex, int sizeLimit, int slotsRequred) {
		for (int index = startingIndex; index < sizeLimit + slotsRequred; index++) {
			ItemStack blackOut = new ItemStack(BLACKOUT_MATERIAL);
			ItemMeta itemMeta = blackOut.getItemMeta();
			itemMeta.setDisplayName(BLACKOUT_TEXT);
			itemMeta.setLore(new ArrayList<>());
			itemMeta.getLore().add(InvisHelper.convertToInvisibleString(index + ""));
			blackOut.setItemMeta(itemMeta);
			inventory.setItem(index, blackOut);
		}
	}

	/**
	 * 
	 * @param item
	 * @param suffix
	 * @return
	 */
	protected String getMaterialSettingConfigKey(ItemStack item, String suffix) {
		
		ComplexMaterial complexMaterial = new ComplexMaterial(item);
		
		String key = MessageFormat.format(MATERIAL_CONFIG_KEY_FORMAT, complexMaterial.toString(ComplexMaterial.TO_MAT), suffix);
		
		if(getConfig().contains(key)){
			return key;
		}
		
		key = MessageFormat.format(MATERIAL_CONFIG_KEY_FORMAT, complexMaterial.toString(ComplexMaterial.TO_MAT_HIDE_ZERO), suffix);
		
		return key;
	}

	///////////////////////
	// RETREIVAL////////////
	///////////////////////

	/**
	 * 
	 * @param item
	 * @param player
	 * @return
	 */
	public Pocket getPocket(ItemStack item) {

		debugInfo("Looking for pocket in: " + (item != null ? item.getType().name() : "null"));

		NBTItem nbtItem = new NBTItem(item);
		if (nbtItem != null && nbtItem.hasKey(POCKET_NBT_JSON_KEY)) {
			Pocket pocket = nbtItem.getObject(POCKET_NBT_JSON_KEY, Pocket.class);

			if (pocket == null) {
				pocket = new Pocket();
			}

			return pocket;
		}

		return null;
	}

	/**
	 * 
	 * @param player
	 */
	public void openPlayersYourPocketsInventory(HumanEntity player) {
		if (player == null) {
			return;
		}

		Map<Integer, List<ItemStack>> contentsMap = new HashMap<>();

		if (player.getInventory() != null && player.getInventory().getContents() != null) {
			for (ItemStack item : player.getInventory().getContents()) {
				if (item == null) {
					continue;
				}

				Pocket pocket = getPocket(item);
				if (pocket != null) {
					int pocketSize = pocket.getContents() != null ? pocket.getContents().size() : 0;

					if (!contentsMap.containsKey(pocketSize)) {
						contentsMap.put(pocketSize, new ArrayList<>());
					}

					contentsMap.get(pocketSize).add(item);
				}
			}
		}

		List<ItemStack> contentsList = new ArrayList<>();

		if (!contentsMap.isEmpty()) {
			SortedSet<Integer> sortedSet = new TreeSet<Integer>(contentsMap.keySet());
			for (Integer size : sortedSet) {
				List<ItemStack> contentsListForCurrentSize = contentsMap.get(size);
				contentsList.addAll(contentsListForCurrentSize);
			}
		}

		ItemStack[] contents = contentsList.toArray(new ItemStack[contentsList.size()]);
		String yourPocketsInventoryName = getPlayersYourPocketsInventoryName(player);

		int rows = (contents.length / INVENTORY_COLUMNS) + (contents.length % INVENTORY_COLUMNS > 0 ? 1 : 0);

		rows = rows < 1 ? 1 : rows;

		Inventory yourPocketsInventory = Bukkit.createInventory(null, rows * INVENTORY_COLUMNS,
				yourPocketsInventoryName);
		yourPocketsInventory.setContents(contents);
		fillWithBlackOutItems(yourPocketsInventory, contents.length, rows * INVENTORY_COLUMNS, 0);

		player.openInventory(yourPocketsInventory);
	}

	/**
	 * 
	 * @param player
	 * @return
	 */
	protected String getPlayersYourPocketsInventoryName(HumanEntity player) {
		String yourPocketsInventoryName = plugin
				.getLocalizedMessage(LocalizedMessageEnum.YOUR_POCKETS_INVENTORY_NAME.getKey(), player);
		return InvisHelper.convertToInvisibleString(YOUR_POCKETS_HIDDEN_LORE_KEY) + ChatColor.RESET
				+ yourPocketsInventoryName;
	}

	/**
	 * 
	 * @param pocket
	 * @return
	 */
	public List<ItemStack> getPocketContents(Pocket pocket) {
		return pocket == null || pocket.getContents() == null ? null
				: pocket.getContents().stream().filter(c -> c != null).map(c -> c.unbox()).collect(Collectors.toList());
	}

	///////////////////////
	// PERSISTENCE//////////
	///////////////////////

	/**
	 * 
	 * @param itemWithPocket
	 * @param itemsInPocket
	 * @param player
	 * @param includePrefix
	 * @return
	 */
	public ItemStack setPocketNbt(ItemStack itemWithPocket, List<ItemStack> itemsInPocket, HumanEntity player,
			boolean includePrefix) {

		debugInfo("NBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBNTNBTNBTNBTNBTNBT");
		debugInfo("###################### setPocketNbt: " + (itemsInPocket != null ? itemsInPocket.size() : ""));
		debugInfo("NBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBTNBNTNBTNBTNBTNBTNBT");

		if (itemWithPocket == null) {
			return null;
		}

		debugInfo("Type: " + itemWithPocket.getType().name());

		List<CardboardBox> contents = null;

		if (itemsInPocket != null && !itemsInPocket.isEmpty()) {
			contents = itemsInPocket.stream().filter(i -> i != null).map(i -> new CardboardBox(i))
					.collect(Collectors.toList());
		} else {
			contents = new ArrayList<>();
		}

		Pocket pocket = new Pocket();
		pocket.setContents(contents);

		debugInfo("Contents: " + (contents != null ? contents.size() : 0));

		if (includePrefix)
			setLoreWithPocketSizeAdded(itemWithPocket, contents, player);

		NBTItem nbtItem = new NBTItem(itemWithPocket);

		nbtItem.setObject(POCKET_NBT_JSON_KEY, pocket);

		itemWithPocket = nbtItem.getItem();

		return itemWithPocket;
	}

	/**
	 * 
	 * @param player
	 * @param inventory
	 */
	protected void saveInventoryIntoItem(HumanEntity player, Inventory inventory) {
		saveInventoryIntoItem(player, inventory, false);
	}

	/**
	 * 
	 * @param player
	 * @param inventory
	 * @param isOnClose
	 */
	protected void saveInventoryIntoItem(HumanEntity player, Inventory inventory, boolean isOnClose) {
		ItemStack itemWithPocket = getActivePocketItem(player);

		if (itemWithPocket == null) {
			debugInfo("itemWithPocket == null");
			return;
		}

		ItemStack[] items = inventory.getStorageContents();

		List<ItemStack> itemsInPocketTemp = items == null ? null
				: Arrays.asList(items).stream().filter(i -> !isBlackoutItem(i)).collect(Collectors.toList());

		List<ItemStack> itemsInPocket = null;
		boolean showPocketInPocketWarning = true;
		boolean showIncompatibleWarning = true;
		if (itemsInPocketTemp != null) {
			itemsInPocket = new ArrayList<>();
			for (ItemStack item : itemsInPocketTemp) {

				if (item != null) {

					if (notAllowedIssue(itemWithPocket, item, player, showPocketInPocketWarning)) {
						showPocketInPocketWarning = false;
						tryToFitItemInPlayerInventory(item, player);
						inventory.remove(item);
					} else if (incompatibleIssue(item, player, showIncompatibleWarning)) {
						showIncompatibleWarning = false;
						tryToFitItemInPlayerInventory(item, player);
						inventory.remove(item);
					} else {
						itemsInPocket.add(item);
					}

				}

			}
		}

		if (isOnClose) {
			debugInfo("SAVING on inventory close");
			itemWithPocket = setPocketNbt(itemWithPocket, itemsInPocket, player, true);
		} else {
			debugInfo("SAVING after inventory action");
			itemWithPocket = setPocketNbt(itemWithPocket, itemsInPocket, player, true);
		}

		setActivePocketItem(player, itemWithPocket);
	}

	///////////////////////
	// INVENTORY HELPERS////
	///////////////////////

	/**
	 * 
	 * @param item
	 * @return
	 */
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

	/**
	 * 
	 * @param item
	 * @param player
	 */
	protected void tryToFitItemInPlayerInventory(ItemStack item, HumanEntity player) {
		HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(item);
		debugWarning("tryToFitItemInPlayerInventory: " + item.getType() + "[" + item.getAmount() + "]");
		if (remaining == null || remaining.values() == null || remaining.values().isEmpty()) {
			remaining.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
		}
	}

	///////////////////////
	// INVIS DATA HELPERS////
	///////////////////////

	/**
	 * 
	 * @param contents
	 * @param player
	 * @return
	 */
	protected String getPocketSizeLore(List<CardboardBox> contents, HumanEntity player) {
		int itemCount = contents != null ? contents.size() : 0;

		IRecipe recipe = recipeRegistrar().getRecipeFromKey(PocketsPlugin.POCKET_RECIPE_KEY);
		String pocketName = recipeRegistrar().getNameConsiderLocalization(recipe, player);

		String invisiblePrefix = InvisHelper.convertToInvisibleString(POCKETS_SIZE_HIDDEN_LORE_KEY);

		return invisiblePrefix + plugin.getConfig().getString(ConfigKeyEnum.DEFAULT_POCKET_COLOR.getKey(),
				PocketsPlugin.DEFAULT_POCKET_COLOR) + pocketName + " [" + itemCount + "]";
	}

	/**
	 * 
	 * @param itemWithPocket
	 * @param player
	 * @param invisibelKey
	 * @return
	 */
	protected List<String> removeOldInvisibleLore(ItemStack itemWithPocket, String invisibelKey) {
		return InvisHelper.removeLoreWithInvisibleKey(itemWithPocket, invisibelKey);
	}

	/**
	 * 
	 * @param itemWithPocket
	 * @param contents
	 * @param player
	 */
	protected void setLoreWithPocketSizeAdded(ItemStack itemWithPocket, List<CardboardBox> contents,
			HumanEntity player) {
		List<String> lore = removeOldInvisibleLore(itemWithPocket, POCKETS_SIZE_HIDDEN_LORE_KEY);

		ItemMeta itemMeta = itemWithPocket.getItemMeta();
		lore.add(getPocketSizeLore(contents, player));
		itemMeta.setLore(lore);
		itemWithPocket.setItemMeta(itemMeta);
	}

	/**
	 * 
	 * @param loreLine
	 * @return
	 */
	protected boolean isHiddenRecipeKey(String loreLine) {
		String visibleLine = InvisHelper.convertToVisibleString(loreLine);
		return AbstractBlockTyperRecipe.isHiddenRecipeKey(visibleLine);
	}

	///////////////////////
	// ACTIVE POCKET////
	///////////////////////

	protected static Map<HumanEntity, Inventory> playersWithOpenInventories = new HashMap<HumanEntity, Inventory>();

	/**
	 * 
	 * @param player
	 * @param inventory
	 */
	public static void AddPlayerWithPocketInventoryOpen(HumanEntity player, Inventory inventory) {
		playersWithOpenInventories.put(player, inventory);
	}

	/**
	 * 
	 * @param player
	 */
	public static void RemovePlayerWithPocketInventoryOpen(HumanEntity player) {
		playersWithOpenInventories.remove(player);
	}
	
	/**
	 * 
	 */
	public void saveAllOpenPocketInventories() {
		if (playersWithOpenInventories != null && !playersWithOpenInventories.isEmpty()) {
			playersWithOpenInventories.entrySet().forEach(e -> saveInventoryIntoItem(e.getKey(), e.getValue(), false));
		}
	}

	/**
	 * 
	 * @param player
	 */
	public void saveOpenPocketInventory(HumanEntity player) {
		if (playersWithOpenInventories != null && !playersWithOpenInventories.isEmpty()) {
			saveInventoryIntoItem(player, playersWithOpenInventories.get(player), false);
		}
	}

	/**
	 * 
	 * @param player
	 */
	public void saveLater(HumanEntity player) {
		new BukkitRunnable() {
			@Override
			public void run() {
				saveOpenPocketInventory(player);
			}
		}.runTaskLater(plugin, 1L);
	}

	/**
	 * 
	 * @param player
	 */
	protected void removePlayerWithPocketInventoryOpen(HumanEntity player) {
		if (player == null)
			return;

		debugInfo("############################ REMOVING PLAYER: " + player.getName());

		if (openPocketMap == null)
			openPocketMap = new HashMap<>();

		if (activeInventoryMap == null)
			activeInventoryMap = new HashMap<>();

		openPocketMap.remove(player.getName());
		activeInventoryMap.remove(player.getName());
	}

	/**
	 * 
	 * @param player
	 * @param item
	 * @param inventory
	 */
	protected void setActivePocketItemAndInventory(HumanEntity player, ItemStack item, Inventory inventory) {
		debugInfo("#############################################################");
		debugInfo("#############################################################");
		debugInfo("#############################################################");
		debugInfo("########### Setting    active   item: " + (item != null ? item.getType().name() : "null"));
		debugInfo("########### Setting active inventory: " + (inventory != null ? inventory.getName() : "null"));
		debugInfo("#############################################################");
		debugInfo("#############################################################");
		debugInfo("#############################################################");

		if (openPocketMap == null)
			openPocketMap = new HashMap<>();

		if (activeInventoryMap == null)
			activeInventoryMap = new HashMap<>();

		openPocketMap.put(player.getName(), item);

		if (inventory != null)
			activeInventoryMap.put(player.getName(), inventory);
	}

	/**
	 * 
	 * @param item
	 * @return
	 */
	protected boolean itemIsAnOpenPocket(ItemStack item) {
		if (openPocketMap == null)
			openPocketMap = new HashMap<>();
		return openPocketMap.values().contains(item);
	}

	/**
	 * 
	 * @param player
	 * @return
	 */
	protected ItemStack getActivePocketItem(HumanEntity player) {
		if (openPocketMap == null)
			openPocketMap = new HashMap<>();
		return openPocketMap.get(player.getName());
	}

	/**
	 * 
	 * @param player
	 * @return
	 */
	protected Inventory getActiveInventory(HumanEntity player) {
		return activeInventoryMap.get(player.getName());
	}

	/**
	 * 
	 * @param player
	 * @param item
	 */
	protected void setActivePocketItem(HumanEntity player, ItemStack item) {
		debugInfo("################################### Set active pocket: "
				+ (item != null ? item.getType().name() : "null"));

		Inventory inventory = getActiveInventory(player);
		NBTItem pocketNbtItem = new NBTItem(getActivePocketItem(player));
		String uniqueId = pocketNbtItem.getString(IRecipe.NBT_BLOCKTYPER_UNIQUE_ID);
		if (uniqueId == null) {
			warning("#############################################");
			warning("########### Pocket did not have unique ID!");
			warning("#############################################");
			return;
		} else {
			debugInfo("#############################################");
			debugInfo("Pocket ID: " + uniqueId);
			debugInfo("#############################################");
		}

		if (inventory != null && inventory.getContents() != null) {
			Integer indexWhereMatchLocated = null;

			int index = 0;
			debugInfo("#############################################");
			debugInfo("#############################################");
			debugInfo("#############################################");
			debugInfo("Checking inventory: " + inventory.getName() + "[" + inventory.getContents().length + "]");
			for (ItemStack itemInInventory : inventory.getContents()) {
				if (itemInInventory != null) {
					debugInfo("Item: " + itemInInventory.getType());
					NBTItem nbtItem = new NBTItem(itemInInventory);
					String nbtUniqueId = nbtItem.getString(IRecipe.NBT_BLOCKTYPER_UNIQUE_ID);
					debugInfo("nbtUniqueId: " + nbtUniqueId);
					if (uniqueId.equals(nbtUniqueId)) {
						indexWhereMatchLocated = index;
					}
				}
				index++;
			}

			debugInfo("#############################################");
			debugInfo("#############################################");

			if (indexWhereMatchLocated != null) {
				ItemStack[] contents = inventory.getContents();
				contents[indexWhereMatchLocated] = new NBTItem(item).getItem();
				inventory.setContents(contents);
				setActivePocketItemAndInventory(player, contents[indexWhereMatchLocated], null);
			} else {
				warning("#############################################");
				warning("Pocket could not be located in inventory!");
				warning("#############################################");
				return;
			}
		} else {
			warning("#############################################");
			warning("Inventory was null or empty!");
			warning("#############################################");
		}
	}

	///////////////////////
	// CONVERSION UTILS////
	///////////////////////

	/**
	 * 
	 * @param itemWithPocket
	 * @param pocket
	 * @param player
	 * @return
	 */
	private ItemStack convertOldItemWithPocket(ItemStack itemWithPocket, Pocket pocket, HumanEntity player) {

		removeOldInvisibleLore(itemWithPocket, POCKETS_HIDDEN_LORE_KEY);
		setLoreWithPocketSizeAdded(itemWithPocket, pocket.getContents(), player);

		NBTItem nbtItem = new NBTItem(itemWithPocket);
		debugInfo("Setting Old pocket as object: " + itemWithPocket.getType().name());
		nbtItem.setObject(POCKET_NBT_JSON_KEY, pocket);
		nbtItem.setString(IRecipe.NBT_BLOCKTYPER_UNIQUE_ID, UUID.randomUUID().toString());
		debugInfo("Done setting Old pocket as object");
		itemWithPocket = nbtItem.getItem();
		debugInfo("Getting item back from Old pocket as object");
		return itemWithPocket;
	}

	/**
	 * 
	 * @param item
	 * @param player
	 * @return
	 */
	protected ItemStack oldPocketConverter(ItemStack item, HumanEntity player) {

		if (item == null)
			return null;

		boolean isVersion1Pocket = false;

		if (!plugin.isNbtItemAPICompatible()) {
			debugInfo("NBTAPI not compatible");
			return item;
		}

		if (item == null || item.getType().equals(Material.AIR) || item.getItemMeta() == null) {
			return null;
		}

		IRecipe pocketRecipe = recipeRegistrar().getRecipeFromKey(PocketsPlugin.POCKET_RECIPE_KEY);

		Pocket pocket = oldPocketHelper.getPocketOld(item, player);

		if (pocket != null) {
			return convertOldItemWithPocket(item, pocket, player);
		} else if (pocketRecipe != null && item.getItemMeta().getDisplayName() != null
				&& item.getItemMeta().getDisplayName().equals(pocketRecipe.getName())) {
			isVersion1Pocket = true;
		}

		NBTItem nbtItem = new NBTItem(item);

		String recipesNbtKey = getRecipesNbtKey();

		// latest version
		if (nbtItem.hasKey(getRecipesNbtKey())) {
			debugInfo(getRecipesNbtKey() + " found: " + nbtItem.getString((getRecipesNbtKey())));
		}
		// last version
		else if (nbtItem.hasKey(IRecipe.NBT_BLOCKTYPER_RECIPE_KEY)) {
			nbtItem.setString(recipesNbtKey, nbtItem.getString(IRecipe.NBT_BLOCKTYPER_RECIPE_KEY));
			nbtItem.removeKey(IRecipe.NBT_BLOCKTYPER_RECIPE_KEY);
			return nbtItem.getItem();
		} else if (isVersion1Pocket) {
			String recipeKey = PocketsPlugin.POCKET_RECIPE_KEY;
			nbtItem.setString(recipesNbtKey, recipeKey);
			debugInfo(recipesNbtKey + " set isVersion1Pocket: " + recipeKey);
			return nbtItem.getItem();
		} else if (item.getItemMeta().getLore() != null && !item.getItemMeta().getLore().isEmpty()) {
			Optional<String> optional = item.getItemMeta().getLore().stream().filter(l -> isHiddenRecipeKey(l))
					.findFirst();
			if (optional != null && optional.isPresent()) {
				String recipeKey = AbstractBlockTyperRecipe.getKeyFromLoreLine(optional.get());
				nbtItem.setString(recipesNbtKey, recipeKey);
				debugInfo(recipesNbtKey + " set: " + recipeKey);
				return nbtItem.getItem();
			}
		}

		return null;
	}
}
