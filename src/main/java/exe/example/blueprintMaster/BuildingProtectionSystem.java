package exe.example.blueprintMaster;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.util.BoundingBox;

import java.util.Iterator;

public class BuildingProtectionSystem implements Listener {
    private final BuildingHealthSystem healthSystem;

    public BuildingProtectionSystem(BuildingHealthSystem healthSystem) {
        this.healthSystem = healthSystem;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isProtectedBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(ChatColor.RED + "建筑尚未被摧毁，无法破坏！");
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block ->
                isProtectedBlock(block.getLocation())
        );
    }

    // 关键修改：血量大于0时保护
    private boolean isProtectedBlock(Location loc) {
        BuildingHealthData data = healthSystem.getBuildingAtLocation(loc);
        if (data == null) return false;

        // 使用精确边界框而非粗略检测
        BoundingBox exactBounds = data.getExactBoundingBox();
        return exactBounds.contains(loc.toVector()) &&
                data.getHealth() > 0 &&
                !data.isCollapsing();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerAttackBuilding(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof ArmorStand)) return;

        Player player = (Player) event.getDamager();
        ArmorStand armorStand = (ArmorStand) event.getEntity();

        if (!healthSystem.isBuildingEntity(armorStand)) return;

        BuildingHealthData data = healthSystem.getBuildingData(armorStand);
        if (data == null) return;

        // 血量大于0时才保护
        if (data.getHealth() > 0 && !data.isCollapsing()) {
            // 使用建筑边界框距离判断攻击位置
            double distance = RotationUtil.calculateDistanceToBounds(
                    player.getLocation(),
                    data.getExactBoundingBox()
            );

            if (distance > 2.0) { // 2格外的攻击无效
                player.sendActionBar(ChatColor.RED + "靠近建筑才能攻击！");
                event.setCancelled(true);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        // 过滤掉苦力怕爆炸（已在自定义逻辑中处理）
        if (event.getEntity() instanceof Creeper) {
            event.setCancelled(true);
            return;
        }

        // 防止其他爆炸破坏建筑方块
        Iterator<Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (isProtectedBlock(block.getLocation())) {
                iterator.remove();
            }
        }
    }
}