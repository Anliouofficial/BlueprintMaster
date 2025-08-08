package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class ProjectileDamageListener implements Listener {
    private static final double PROJECTILE_ATTACK_RANGE = 2.0;

    private final JavaPlugin plugin;
    private final BuildingHealthSystem healthSystem;
    private final BuildingMonsterAttackSystem attackSystem;

    public ProjectileDamageListener(JavaPlugin plugin, BuildingHealthSystem healthSystem,
                                    BuildingMonsterAttackSystem attackSystem) {
        this.plugin = plugin;
        this.healthSystem = healthSystem;
        this.attackSystem = attackSystem;
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location hitLocation = projectile.getLocation();

        // === 处理女巫药水 ===
        if (projectile instanceof ThrownPotion potion) {
            PersistentDataContainer pdc = potion.getPersistentDataContainer();

            // 检查是否是女巫投掷的药水
            if (pdc.has(new NamespacedKey(plugin, "is_witch_potion"), PersistentDataType.BYTE)) {
                handleWitchPotionImpact(potion, hitLocation);
                event.setCancelled(true); // 取消原版药水效果
                return;
            }
        }

        // 处理火焰弹伤害
        if (projectile instanceof LargeFireball) {
            handleFireballDamage((LargeFireball) projectile, hitLocation);
        }

        // 修改点1: 增强建筑碰撞检测
        BuildingHealthData data = healthSystem.getBuildingAtLocation(hitLocation);

        // 如果直接命中位置未检测到建筑，尝试检测碰撞点
        if (data == null) {
            if (event.getHitBlock() != null) {
                data = healthSystem.getBuildingAtLocation(event.getHitBlock().getLocation());
            } else if (event.getHitEntity() != null) {
                data = healthSystem.getBuildingAtLocation(event.getHitEntity().getLocation());
            }
        }

        // 处理其他投射物伤害
        if (projectile.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "building_damage"),
                PersistentDataType.DOUBLE)) {

            double rawDamage = projectile.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "building_damage"),
                    PersistentDataType.DOUBLE);

            if (data != null) {
                // 修改点2: 计算精确距离
                double distance = RotationUtil.calculateExactDistanceToSurface(hitLocation, data);

                // 修改点3: 只有实际接触到建筑表面才造成伤害
                if (distance <= PROJECTILE_ATTACK_RANGE) {
                    double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
                    data.applyDamage(actualDamage);

                    projectile.getWorld().spawnParticle(Particle.BLOCK,
                            hitLocation,
                            20,
                            0.5, 0.5, 0.5, 0.1,
                            Material.STONE.createBlockData());

                    projectile.getWorld().spawnParticle(
                            Particle.DAMAGE_INDICATOR,
                            hitLocation.add(0, 1, 0),
                            5,
                            0.3, 0.5, 0.3,
                            0.1
                    );

                    // 确保血量归零时触发崩塌
                    if (data.getHealth() <= 0) {
                        healthSystem.getCollapseSystem().collapseBuilding(data);
                    }
                }
            }
        }
    }

    private void handleWitchPotionImpact(ThrownPotion potion, Location hitLocation) {
        double rawDamage = 0.0;
        if (potion.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "witch_damage"), PersistentDataType.DOUBLE)) {
            rawDamage = potion.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "witch_damage"), PersistentDataType.DOUBLE);
        }

        // 处理建筑伤害
        BuildingHealthData data = healthSystem.getBuildingAtLocation(hitLocation);
        if (data != null) {
            // 计算精确距离
            double distance = RotationUtil.calculateExactDistanceToSurface(hitLocation, data);

            // 只有实际接触建筑才造成伤害
            if (distance <= PROJECTILE_ATTACK_RANGE) {
                double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
                data.applyDamage(actualDamage);

                // 创建爆炸效果
                hitLocation.getWorld().spawnParticle(
                        Particle.EXPLOSION,
                        hitLocation,
                        8,
                        0.5, 0.5, 0.5,
                        0.1
                );
                hitLocation.getWorld().playSound(
                        hitLocation,
                        Sound.ENTITY_GENERIC_EXPLODE,
                        0.8f,
                        1.2f
                );

                // 触发崩塌
                if (data.getHealth() <= 0) {
                    healthSystem.getCollapseSystem().collapseBuilding(data);
                }
            }
        }

        // 处理生物效果
        attackSystem.applyPotionEffects(potion, hitLocation);
    }

    private void handleFireballDamage(LargeFireball fireball, Location hitLocation) {
        PersistentDataContainer pdc = fireball.getPersistentDataContainer();
        if (!pdc.has(new NamespacedKey(plugin, "building_damage"), PersistentDataType.DOUBLE)) {
            return;
        }

        double rawDamage = pdc.get(new NamespacedKey(plugin, "building_damage"), PersistentDataType.DOUBLE);

        UUID buildingId = null;
        if (pdc.has(new NamespacedKey(plugin, "target_building"), PersistentDataType.STRING)) {
            String idStr = pdc.get(new NamespacedKey(plugin, "target_building"), PersistentDataType.STRING);
            buildingId = UUID.fromString(idStr);
        }

        BuildingHealthData data = null;
        if (buildingId != null) {
            data = healthSystem.getBuildings().get(buildingId);
            if (data != null && (data.isCollapsing() || data.getHealth() <= 0)) {
                return;
            }
        }

        if (data == null) {
            data = healthSystem.getBuildingAtLocation(hitLocation);
            if (data != null && (data.isCollapsing() || data.getHealth() <= 0)) {
                return;
            }
        }

        if (data != null) {
            // 改为仅播放粒子效果（无伤害）
            hitLocation.getWorld().spawnParticle(
                    Particle.SMOKE,
                    hitLocation,
                    10,
                    0.5, 0.5, 0.5,
                    0.1
            );
        }
    }
}