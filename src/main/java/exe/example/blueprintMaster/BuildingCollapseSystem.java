// 文档3: BuildingCollapseSystem.java
package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class BuildingCollapseSystem {
    private final JavaPlugin plugin;
    private final Map<UUID, BuildingHealthData> buildings;
    private final Map<UUID, LivingEntity> buildingEntities;
    private final BuildingHealthSystem healthSystem;
    private final BuildingMonsterAttackSystem monsterAttackSystem;

    public BuildingCollapseSystem(JavaPlugin plugin,
                                  Map<UUID, BuildingHealthData> buildings,
                                  Map<UUID, LivingEntity> buildingEntities,
                                  BuildingHealthSystem healthSystem,
                                  BuildingMonsterAttackSystem monsterAttackSystem) {
        this.plugin = plugin;
        this.buildings = buildings;
        this.buildingEntities = buildingEntities;
        this.healthSystem = healthSystem;
        this.monsterAttackSystem = monsterAttackSystem;
    }

    public void collapseBuilding(BuildingHealthData data) {
        if (data.isCollapsing()) return;

        data.setCollapsing(true);
        LivingEntity entity = buildingEntities.get(data.getBuildingId());

        if (entity != null) {
            entity.remove();
            buildingEntities.remove(data.getBuildingId());
        }

        monsterAttackSystem.assignDropMissions(data.getCenter());

        // 移除爆炸音效和粒子
        data.getBottomCenter().getWorld().playSound(
                data.getBottomCenter(),
                Sound.BLOCK_GLASS_BREAK, // 改为玻璃破碎音效
                2.0f,
                0.8f
        );

        new BuildingCollapseTask(plugin, data, this, healthSystem).runTaskTimer(plugin, 0, 2);
    }

    private class BuildingCollapseTask extends BukkitRunnable {
        private final JavaPlugin plugin;
        private final BuildingHealthData data;
        private final BuildingCollapseSystem collapseSystem;
        private final BuildingHealthSystem healthSystem;
        private int currentY;
        private final Random random = new Random();
        private final List<Location>[] layers; // 预计算所有层的方块位置
        private int currentLayerIndex;
        public BuildingCollapseTask(JavaPlugin plugin,
                                    BuildingHealthData data,
                                    BuildingCollapseSystem collapseSystem,
                                    BuildingHealthSystem healthSystem) {
            this.plugin = plugin;
            this.data = data;
            this.collapseSystem = collapseSystem;
            this.healthSystem = healthSystem;
            this.currentY = data.getHeight() - 1;
            this.layers = new List[data.getHeight()];
            for (int y = 0; y < data.getHeight(); y++) {
                layers[y] = new ArrayList<>();
                for (int z = 0; z < data.getLength(); z++) {
                    for (int x = 0; x < data.getWidth(); x++) {
                        Location loc = RotationUtil.calculateBlockPosition(
                                data.getBottomCenter(),
                                x, y, z,
                                data.getWidth(), data.getLength(),
                                data.getRotation()
                        );
                        layers[y].add(loc);
                    }
                }
            }
            this.currentLayerIndex = data.getHeight() - 1;
        }

        @Override
        public void run() {
            World world = data.getCenter().getWorld();

            if (currentY < 0) {

                Location bottomCenter = data.getBottomCenter();
                healthSystem.completelyRemoveBuilding(data.getBuildingId());

                if (plugin instanceof TemplateManagerPlugin) {
                    ((TemplateManagerPlugin) plugin).removePermanentOutline(bottomCenter);
                }

                this.cancel();
                return;
            }

            destroyLayer(currentY, world);

            Location particleLoc = data.getBottomCenter().clone().add(0, currentY, 0);
            world.spawnParticle(
                    Particle.CLOUD,
                    particleLoc,
                    20,
                    data.getWidth()/2.0,
                    0.5,
                    data.getLength()/2.0,
                    0
            );

            world.playSound(
                    particleLoc,
                    Sound.BLOCK_GRAVEL_BREAK, // 改为砂砾破碎音效
                    0.8f,
                    0.5f + (currentY / (float) data.getHeight())
            );

            currentY--;
            LivingEntity entity = buildingEntities.get(data.getBuildingId());
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }

        // 修改方法签名添加world参数
        private void destroyLayer(int y, World world) {
            List<Location> layer = layers[y];
            for (Location loc : layer) {
                Block block = loc.getBlock();
                Material material = block.getType();

                if (material.isAir() || material == Material.FIRE) {
                    continue;
                }

                world.spawnParticle(
                        Particle.BLOCK,
                        loc,
                        15, // 减少粒子数量
                        0.4, 0.4, 0.4, 0.05, // 缩小粒子范围
                        material.createBlockData()
                );

                // 移除方块掉落逻辑 - 直接设置方块为空气
                block.setType(Material.AIR);
            }
        }
    }
}