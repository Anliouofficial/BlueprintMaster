package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingRepairSystem implements Listener {
    private final TemplateManagerPlugin plugin;
    private final Map<UUID, RepairSession> repairSessions = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Double> repairHeat = new HashMap<>();
    private final Map<UUID, Double> digHeat = new HashMap<>();
    private final Map<UUID, DigSession> digSessions = new HashMap<>();
    private final Map<UUID, Location> diggingBlocks = new HashMap<>();

    private static final double REPAIR_RATE = 3.0;
    private static final double HEAT_RATE = 0.1;
    private static final double MAX_HEAT = 1.0;
    private static final double OVERHEAT_THRESHOLD = 0.8;
    private static final int COOLDOWN_TIME = 3000;
    private static final int REPAIR_TICK = 10;
    private static final int MAX_RANGE = 5;
    private static final int OVERHEAT_DURATION = 100;
    private static final double DIG_SPEED = 0.2;
    private static final double DIG_HEAT_RATE = 0.15;

    public BuildingRepairSystem(TemplateManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.LIGHTNING_ROD) return;
        if (!item.getItemMeta().isUnbreakable()) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            event.setCancelled(true);
            handleDigging(player, event.getClickedBlock());
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);

            if (isOnCooldown(player)) {
                player.sendActionBar(ChatColor.RED + "工具过热中，请等待冷却");
                player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.5f);
                return;
            }

            handleRepairSession(player);
        }
    }

    @EventHandler
    public void onPlayerSwitchItem(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());

        if (newItem == null || newItem.getType() != Material.LIGHTNING_ROD) {
            stopRepairSession(player);
            stopDigSession(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopRepairSession(event.getPlayer());
        stopDigSession(event.getPlayer());
    }

    private void handleDigging(Player player, Block block) {
        UUID playerId = player.getUniqueId();

        if (isOnCooldown(player)) {
            player.sendActionBar(ChatColor.RED + "工具过热中，请等待冷却");
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.5f);
            return;
        }

        BuildingHealthSystem healthSystem = plugin.getHealthSystem();
        BuildingHealthData data = healthSystem.getBuildingAtLocation(block.getLocation());
        if (data == null) {
            player.sendActionBar(ChatColor.RED + "这不是建筑方块");
            return;
        }

        BoundingBox exactBounds = data.getExactBoundingBox();
        if (!exactBounds.contains(block.getLocation().toVector())) {
            player.sendActionBar(ChatColor.RED + "这不是建筑方块");
            return;
        }

        if (!data.getOwnerId().equals(player.getUniqueId())) {
            player.sendActionBar(ChatColor.RED + "这不是你的建筑！");
            return;
        }

        Location blockLoc = block.getLocation();
        if (diggingBlocks.containsKey(playerId) && diggingBlocks.get(playerId).equals(blockLoc)) {
            return;
        }

        DigSession session = new DigSession(player, block, data);
        digSessions.put(playerId, session);
        diggingBlocks.put(playerId, blockLoc);

        session.task = new DigTask(session).runTaskTimer(plugin, 0, 5);
        player.sendActionBar(ChatColor.GOLD + "开始挖掘...");
        player.playSound(player.getLocation(), Sound.BLOCK_STONE_HIT, 0.8f, 1.0f);
    }

    private void stopDigSession(Player player) {
        UUID playerId = player.getUniqueId();
        if (!digSessions.containsKey(playerId)) return;

        DigSession session = digSessions.remove(playerId);
        diggingBlocks.remove(playerId);

        if (session.task != null) {
            session.task.cancel();
        }

        player.sendActionBar(ChatColor.YELLOW + "已停止挖掘");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f);
    }

    private void handleRepairSession(Player player) {
        UUID playerId = player.getUniqueId();

        // 修复：提升building变量作用域
        BuildingHealthData building = null;

        if (repairSessions.containsKey(playerId)) {
            stopRepairSession(player);
            return;
        }

        BuildingHealthSystem healthSystem = plugin.getHealthSystem();
        double minDistance = Double.MAX_VALUE;

        for (BuildingHealthData data : healthSystem.getBuildings().values()) {
            if (data.getHealth() >= data.getMaxHealth()) continue;

            double dist = player.getLocation().distance(data.getCenter());
            if (dist < minDistance && dist < MAX_RANGE) {
                minDistance = dist;
                building = data;
            }
        }

        if (building == null) {
            player.sendActionBar(ChatColor.RED + "附近没有需要维修的建筑");
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.5f);
            return;
        }

        if (!building.getOwnerId().equals(player.getUniqueId())) {
            player.sendActionBar(ChatColor.RED + "这不是你的建筑！");
            player.spawnParticle(Particle.DUST, building.getCenter(), 10,
                    new Particle.DustOptions(Color.RED, 1.0f));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        RepairSession session = new RepairSession(player, building);
        repairSessions.put(playerId, session);
        session.task = new RepairTask(session).runTaskTimer(plugin, 0, REPAIR_TICK);

        player.sendActionBar(ChatColor.GREEN + "开始维修...");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
    }

    private void stopRepairSession(Player player) {
        UUID playerId = player.getUniqueId();
        if (!repairSessions.containsKey(playerId)) return;

        RepairSession session = repairSessions.remove(playerId);
        if (session.task != null) {
            session.task.cancel();
        }

        repairHeat.put(playerId, Math.max(1.0, repairHeat.getOrDefault(playerId, 1.0)));
        player.sendActionBar(ChatColor.YELLOW + "已停止维修");
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f);
    }

    private boolean isOnCooldown(Player player) {
        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd == null) return false;

        if (System.currentTimeMillis() >= cooldownEnd) {
            cooldowns.remove(player.getUniqueId());
            repairHeat.remove(player.getUniqueId());
            digHeat.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void updateHeatDisplay(Player player, double heat, String type) {
        int heatBars = (int) (heat * 10);
        StringBuilder heatBar = new StringBuilder(ChatColor.GOLD + type + "热量: [");
        heatBar.append(heat >= OVERHEAT_THRESHOLD ? ChatColor.RED : ChatColor.YELLOW);

        for (int i = 0; i < 10; i++) {
            heatBar.append(i < heatBars ? "|" : "·");
        }
        heatBar.append(ChatColor.GOLD + "]");

        Long cooldownEnd = cooldowns.get(player.getUniqueId());
        if (cooldownEnd != null) {
            int remaining = (int) ((cooldownEnd - System.currentTimeMillis()) / 1000);
            heatBar.append(ChatColor.GRAY + " 冷却: " + remaining + "秒");
        }

        player.sendActionBar(heatBar.toString());
    }

    private void cooldownHeat(UUID playerId) {
        new BukkitRunnable() {
            @Override
            public void run() {
                repairHeat.put(playerId, 0.0);
                digHeat.put(playerId, 0.0);
            }
        }.runTaskLater(plugin, COOLDOWN_TIME / 50);
    }

    private class DigSession {
        final Player player;
        final Block block;
        final BuildingHealthData buildingData;
        BukkitTask task;
        double progress = 0.0;
        double heat = 0.0;

        DigSession(Player player, Block block, BuildingHealthData buildingData) {
            this.player = player;
            this.block = block;
            this.buildingData = buildingData;
        }
    }

    private class RepairSession {
        final Player player;
        final BuildingHealthData building;
        BukkitTask task;
        double heat = 0.0;
        int repairTicks = 0;

        RepairSession(Player player, BuildingHealthData building) {
            this.player = player;
            this.building = building;
        }
    }

    private class DigTask extends BukkitRunnable {
        private final DigSession session;

        DigTask(DigSession session) {
            this.session = session;
        }

        @Override
        public void run() {
            Player player = session.player;
            Block block = session.block;
            BuildingHealthData buildingData = session.buildingData;

            if (player.getLocation().distance(block.getLocation()) > MAX_RANGE) {
                stopDigSession(player);
                return;
            }

            session.progress += DIG_SPEED;
            session.heat += DIG_HEAT_RATE;

            if (session.heat >= MAX_HEAT) {
                overheatDig(player);
                return;
            }

            showDigProgress(player, block, session.progress);
            updateHeatDisplay(player, session.heat, "挖掘");

            if (session.progress >= 1.0) {
                completeDigging(player, block, buildingData);
            }
        }

        private void showDigProgress(Player player, Block block, double progress) {
            Location loc = block.getLocation().add(0.5, 1.2, 0.5);
            int bars = (int) (progress * 10);

            StringBuilder progressBar = new StringBuilder(ChatColor.GOLD.toString());
            for (int i = 0; i < 10; i++) {
                progressBar.append(i < bars ? "|" : "·");
            }

            player.spawnParticle(Particle.NOTE, loc, 1);
            player.sendActionBar(ChatColor.YELLOW + "挖掘中: " + progressBar.toString());
        }

        private void completeDigging(Player player, Block block, BuildingHealthData data) {
            block.setType(Material.AIR);
            double damagePerBlock = 5.0;
            data.applyDamage(damagePerBlock);

            if (data.getHealth() <= 0 && !data.isCollapsing()) {
                plugin.getHealthSystem().getCollapseSystem().collapseBuilding(data);
            }

            player.playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
            block.getWorld().spawnParticle(Particle.BLOCK,
                    block.getLocation().add(0.5, 0.5, 0.5),
                    20, 0.5, 0.5, 0.5, 0.1,
                    block.getType().createBlockData()
            );
            stopDigSession(player);
        }

        private void overheatDig(Player player) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + COOLDOWN_TIME);
            digHeat.put(player.getUniqueId(), MAX_HEAT);

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    OVERHEAT_DURATION,
                    2,
                    false, false, false
            ));

            player.sendActionBar(ChatColor.RED + "工具过热！挖掘停止");
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.5f);
            player.spawnParticle(Particle.LAVA, player.getLocation(), 20);
            stopDigSession(player);
        }
    }

    private class RepairTask extends BukkitRunnable {
        private final RepairSession session;

        RepairTask(RepairSession session) {
            this.session = session;
        }

        @Override
        public void run() {
            Player player = session.player;
            BuildingHealthData building = session.building;

            if (building.getHealth() <= 0) {
                plugin.getHealthSystem().getCollapseSystem().collapseBuilding(building);
                stopRepairSession(player);
                return;
            }

            // 修复：使用新的变量名避免冲突
            double distToBuilding = player.getLocation().distance(building.getCenter());
            if (distToBuilding > MAX_RANGE || building.getHealth() >= building.getMaxHealth()) {
                stopRepairSession(player);
                return;
            }

            session.repairTicks += REPAIR_TICK;

            if (session.repairTicks % 20 == 0) {
                double newHeat = session.heat + HEAT_RATE * (1 + session.heat);
                if (newHeat >= MAX_HEAT) {
                    overheatRepair(player);
                    return;
                }
                session.heat = newHeat;
            }

            int slownessLevel = session.heat >= OVERHEAT_THRESHOLD ? 2 : 1;
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    25,
                    slownessLevel,
                    false, false, false
            ));

            double repairAmount = REPAIR_RATE * (session.heat > OVERHEAT_THRESHOLD ? 0.5 : 1.0);
            double newHealth = Math.min(building.getMaxHealth(), building.getHealth() + repairAmount);
            building.setHealth(newHealth);

            Location buildingCenter = building.getCenter();
            player.spawnParticle(Particle.HAPPY_VILLAGER, buildingCenter, 5, 1, 1, 1, 0.1);
            updateHeatDisplay(player, session.heat, "维修");

            if (session.heat > OVERHEAT_THRESHOLD) {
                player.spawnParticle(Particle.SMOKE, player.getEyeLocation(), 3);
                if (session.repairTicks % 40 == 0) {
                    player.sendActionBar(ChatColor.GOLD + "工具过热！效率降低");
                }
            }

            plugin.getHealthSystem().showHealthBar(building, true, true);
        }

        private void overheatRepair(Player player) {
            UUID playerId = player.getUniqueId();
            cooldowns.put(playerId, System.currentTimeMillis() + COOLDOWN_TIME);
            repairHeat.put(playerId, MAX_HEAT);

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    OVERHEAT_DURATION,
                    3,
                    false, false, false
            ));

            player.sendActionBar(ChatColor.RED + "工具过热！维修停止");
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.5f);
            player.spawnParticle(Particle.LAVA, player.getLocation(), 20);
            stopRepairSession(player);
        }
    }
}