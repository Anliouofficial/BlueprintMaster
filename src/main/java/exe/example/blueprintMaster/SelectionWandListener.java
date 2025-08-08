// 文档3: SelectionWandListener.java (不变)
package exe.example.blueprintMaster;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SelectionWandListener implements Listener {
    private final TemplateManagerPlugin plugin;

    public SelectionWandListener(TemplateManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.STICK) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        if (meta.getDisplayName().equals(ChatColor.GREEN + "选区工具")) {
            event.setCancelled(true);
            Selection selection = plugin.getSelection(player.getUniqueId());

            if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
                selection.setPos1(event.getClickedBlock().getLocation());
                player.sendMessage(ChatColor.GREEN + "设置第一点: " + formatLocation(event.getClickedBlock().getLocation()));
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                selection.setPos2(event.getClickedBlock().getLocation());
                player.sendMessage(ChatColor.GREEN + "设置第二点: " + formatLocation(event.getClickedBlock().getLocation()));
            }
        }
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}