package com.blocktyper.pockets;

import java.util.List;

import org.bukkit.Achievement;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAchievementAwardedEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryOpenListener extends PocketsListenerBase {

	static final ClickType DEFAULT_CLICK_TYPE = ClickType.RIGHT;

	public InventoryOpenListener(PocketsPlugin plugin) {
		super(plugin);
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
	public void inventoryOpenEvent(InventoryOpenEvent event) {

		plugin.debugInfo("InventoryOpenEvent");

		if (event.getInventory() == null || event.getInventory().getContents() == null) {
			return;
		}

		HumanEntity player = event.getPlayer();

		if (player == null && event.getInventory().getViewers() != null
				&& !event.getInventory().getViewers().isEmpty()) {
			player = event.getInventory().getViewers().get(0);
		}

		if (player == null) {
			return;
		}

		for (ItemStack item : event.getInventory().getContents()) {
			if (item != null) {
				Pocket pocket = getPocket(item, event.getInventory().getViewers().get(0));
				if (pocket == null) {
					continue;
				}

				List<ItemStack> contents = getPocketContents(pocket);
				setPocketJson(item, contents, event.getInventory().getViewers().get(0), true);
			}
		}
	}
	
	
	
	
	@EventHandler
	public void onJoin(PlayerJoinEvent event){
	    event.getPlayer().removeAchievement(Achievement.OPEN_INVENTORY);
	}
	 
	@EventHandler
	public void onInventoryOpenEvent(PlayerAchievementAwardedEvent event){
	    if(event.getAchievement().equals(Achievement.OPEN_INVENTORY)){
	        event.setCancelled(true);
	        namePockets(event.getPlayer(), event.getPlayer().getInventory());
	    }
	}
	
	private void namePockets(HumanEntity player, Inventory inventory){
		if (player == null) {
			return;
		}
		
		if (inventory == null || inventory.getContents() == null) {
			return;
		}

		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null) {
				Pocket pocket = getPocket(item, player);
				if (pocket == null) {
					continue;
				}

				List<ItemStack> contents = getPocketContents(pocket);
				setPocketJson(item, contents, player, true);
			}
		}
	}

}