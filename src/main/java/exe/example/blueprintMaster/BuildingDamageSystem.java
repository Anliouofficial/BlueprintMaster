package exe.example.blueprintMaster;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingDamageSystem implements Listener {
    private static final double MELEE_ATTACK_RANGE = 1.5;
    private static final double PROJECTILE_ATTACK_RANGE = 2.0;
    private final BuildingMonsterAttackSystem attackSystem;
    private final BuildingAggroSystem aggroSystem;
    private final BuildingEntitySystem entitySystem;
    private final BuildingHealthSystem healthSystem;
    private final Map<UUID, Double> pendingDamage = new HashMap<>();

    public BuildingDamageSystem(JavaPlugin plugin,
                                BuildingAggroSystem aggroSystem,
                                BuildingEntitySystem entitySystem,
                                BuildingHealthSystem healthSystem,
                                BuildingMonsterAttackSystem attackSystem) {
        this.aggroSystem = aggroSystem;
        this.entitySystem = entitySystem;
        this.healthSystem = healthSystem;
        this.attackSystem = attackSystem;
    }

    private void handleDamage(EntityDamageEvent event, BuildingHealthData data) {
        if (!(event.getEntity() instanceof LivingEntity)) return;

        LivingEntity entity = (LivingEntity) event.getEntity();
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1.0f, 1.0f);
        entity.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, entity.getLocation(), 10);

        // 修复：确保damageLocation被正确定义
        Location damageLocation = null;
        if (event instanceof EntityDamageByEntityEvent damageByEntity) {
            Entity damager = damageByEntity.getDamager();
            damageLocation = damager.getLocation();
        }

        if (damageLocation == null) {
            damageLocation = data.getCenter();
        }

        double exactDistance = calculateExactDistanceToSurface(damageLocation, data);
        boolean isValidAttack = false; // 只定义一次
        if (event instanceof EntityDamageByEntityEvent damageByEntity) {
            Entity damager = damageByEntity.getDamager();

            if (damager instanceof LivingEntity) {
                // 近战攻击必须在1格范围内
                isValidAttack = exactDistance <= MELEE_ATTACK_RANGE;
            }
            else if (damager instanceof Projectile) {
                // 投射物必须在2格范围内
                isValidAttack = exactDistance <= PROJECTILE_ATTACK_RANGE;
            }
        }
        if (data == null || data.isCollapsing()) {
            event.setCancelled(true);
            return;
        }

        if (!isValidAttack) {
            event.setCancelled(true);
            return;
        }

        double rawDamage = event.getDamage();
        double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
        data.applyDamage(actualDamage);

        pendingDamage.merge(data.getBuildingId(), actualDamage, Double::sum);

        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent damageByEntity) {
            Entity damager = damageByEntity.getDamager();

            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player) {
                    attacker = (Player) shooter;
                }
            }
            else if (damager instanceof Player) {
                attacker = (Player) damager;
            }
            else if (damager instanceof LivingEntity livingAttacker) {
                aggroSystem.addAggro(data.getBuildingId(), livingAttacker);
            }
        }

        // 确保血量归零时触发崩塌 (修复位置)
        if (data.getHealth() <= 0 && !data.isCollapsing()) {
            data.setHealth(0);
            healthSystem.getCollapseSystem().collapseBuilding(data);
        }

        event.setCancelled(true);
    }

    private double calculateExactDistanceToSurface(Location location, BuildingHealthData data) {
        BoundingBox exactBounds = data.getExactBoundingBox();
        Vector pos = location.toVector();

        // 计算到建筑六个面的最小距离
        double minDistance = Double.MAX_VALUE;

        // 检查前后两面 (X轴)
        minDistance = Math.min(minDistance, Math.abs(pos.getX() - exactBounds.getMinX()));
        minDistance = Math.min(minDistance, Math.abs(pos.getX() - exactBounds.getMaxX()));

        // 检查左右两面 (Z轴)
        minDistance = Math.min(minDistance, Math.abs(pos.getZ() - exactBounds.getMinZ()));
        minDistance = Math.min(minDistance, Math.abs(pos.getZ() - exactBounds.getMaxZ()));

        // 检查上下两面 (Y轴)
        minDistance = Math.min(minDistance, Math.abs(pos.getY() - exactBounds.getMinY()));
        minDistance = Math.min(minDistance, Math.abs(pos.getY() - exactBounds.getMaxY()));

        return minDistance;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // 关键修复：跳过玩家实体
        if (event.getEntity() instanceof Player) {
            return; // 让倒地插件处理玩家伤害
        }

        // 只处理建筑实体事件
        if (!(event.getEntity() instanceof LivingEntity)) return;

        LivingEntity entity = (LivingEntity) event.getEntity();

        // 如果不是建筑实体，跳过处理
        if (!healthSystem.isBuildingEntity(entity)) {
            return;
        }

        // 获取建筑数据
        BuildingHealthData data = healthSystem.getBuildingData(entity);

        // 检查data是否有效
        if (data == null || data.isCollapsing()) {
            event.setCancelled(true);
            return;
        }

        // 处理建筑伤害
        handleDamage(event, data);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();

        if (attackSystem == null) return;

        // 修复：使用正确的方法名
        if (projectile.getShooter() instanceof LivingEntity shooter) {
            if (!attackSystem.canShooterAttackNow(shooter)) {
                return;
            }
        }
    }
}