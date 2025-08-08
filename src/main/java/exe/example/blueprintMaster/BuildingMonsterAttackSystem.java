package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

public class BuildingMonsterAttackSystem implements Listener {

    private static final double MELEE_ATTACK_RANGE = 1.0;
    private static final double RANGED_ATTACK_RANGE = 15.0;
    private static final double PLAYER_PRIORITY_RANGE = 16.0;
    private static final double MELEE_AGGRO_RANGE = 16.0;
    private static final double RANGED_AGGRO_RANGE = 25.0;
    private static final double CREEPER_EXPLOSION_RANGE = 1.0;
    private static final double BUILDING_BOUNDS_MARGIN = 0.5;
    private static final long PLAYER_TARGET_TIMEOUT = 5000; // 5 seconds
    private static final double MIN_RANGED_DISTANCE = 5.0;  // 新增：远程怪物的最小攻击距离
    private static final double MAX_RANGED_DISTANCE = 20.0; // 新增：远程怪物的最大攻击距离
    private static final double MOVE_AWAY_SPEED = 0.3;      // 新增：远离速度

    private final JavaPlugin plugin;
    private final BuildingHealthSystem healthSystem;
    private final Map<UUID, LivingEntity> buildingEntities;
    private final BuildingAggroSystem aggroSystem;
    private final Map<UUID, Long> lastAttackTimes = new HashMap<>();
    private final Map<UUID, Long> lastPlayerSightTimes = new HashMap<>();
    private final Map<UUID, Location> ghastDropMissions = new HashMap<>();
    private final Map<UUID, Long> playerTargetFailStartTimes = new HashMap<>();

    public BuildingMonsterAttackSystem(JavaPlugin plugin, BuildingHealthSystem healthSystem,
                                       Map<UUID, LivingEntity> buildingEntities,
                                       BuildingAggroSystem aggroSystem) {
        this.plugin = plugin;
        this.healthSystem = healthSystem;
        this.buildingEntities = buildingEntities;
        this.aggroSystem = aggroSystem;
        startAttackTask();
    }

    private void startAttackTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanDestroyedBuildingTargets();
                processBuildingAttacks();
                handleGhastDropMissions();
            }
        }.runTaskTimer(plugin, 0, 10); // Run every half second
    }

    private void handleGhastDropMissions() {
        Iterator<Map.Entry<UUID, Location>> iterator = ghastDropMissions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Location> entry = iterator.next();
            UUID ghastId = entry.getKey();
            Location dropLocation = entry.getValue();

            Entity entity = Bukkit.getEntity(ghastId);
            if (!(entity instanceof Ghast)) {
                iterator.remove();
                continue;
            }

            Ghast ghast = (Ghast) entity;
            Location currentLoc = ghast.getLocation();

            Vector direction = dropLocation.toVector().subtract(currentLoc.toVector()).normalize();
            ghast.setVelocity(direction.multiply(0.5));

            if (currentLoc.distance(dropLocation) < 5.0) {
                World world = dropLocation.getWorld();
                for (int i = 0; i < 6; i++) {
                    Location spawnLoc = dropLocation.clone().add(
                            Math.random() * 4 - 2,
                            0,
                            Math.random() * 4 - 2
                    );
                    spawnLoc.setY(world.getHighestBlockYAt(spawnLoc) + 1);
                    world.spawnEntity(spawnLoc, EntityType.ZOMBIFIED_PIGLIN);
                }

                ghast.remove();
                iterator.remove();
            }
        }
    }

    public void assignDropMissions(Location location) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, 50, 50, 50)) {
            if (entity instanceof Ghast ghast) {
                if (ghastDropMissions.containsKey(ghast.getUniqueId())) continue;
                ghastDropMissions.put(ghast.getUniqueId(), location.clone());
            }
        }
    }

    private void cleanDestroyedBuildingTargets() {
        Iterator<Map.Entry<UUID, LivingEntity>> iterator = buildingEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LivingEntity> entry = iterator.next();
            UUID buildingId = entry.getKey();
            LivingEntity entity = entry.getValue();

            BuildingHealthData data = healthSystem.getBuildings().get(buildingId);
            if (data == null || data.isCollapsing()) {
                clearAllTargets(buildingId, entity.getLocation());
                aggroSystem.removeBuildingAggro(buildingId);
                iterator.remove();
            }
        }
    }

    private void clearAllTargets(UUID buildingId, Location center) {
        aggroSystem.removeBuildingAggro(buildingId);

        for (Entity mob : center.getWorld().getEntities()) {
            if (mob instanceof Mob mobEntity) {
                if (mobEntity.getTarget() != null &&
                        (buildingEntities.containsKey(buildingId) ||
                                mobEntity.getTarget().getLocation().distance(center) < 1.0)) {
                    mobEntity.setTarget(null);
                }
            }
        }
    }

    private List<LivingEntity> getNearbyMobs(Location center, double radius) {
        List<LivingEntity> mobs = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity && isHostileMob(entity)) {
                mobs.add((LivingEntity) entity);
            }
        }
        return mobs;
    }

    private boolean isHostileMob(Entity entity) {
        return entity instanceof Monster ||
                entity instanceof Ghast ||
                entity instanceof Slime ||
                entity instanceof Phantom;
    }

    private void processBuildingAttacks() {
        for (BuildingHealthData data : healthSystem.getBuildings().values()) {
            if (data.isGenerating() || data.getHealth() <= 0 || data.isCollapsing()) continue;

            UUID buildingId = data.getBuildingId();
            LivingEntity buildingEntity = buildingEntities.get(buildingId);
            if (buildingEntity == null) continue;

            BoundingBox buildingBounds = data.getExactBoundingBox().clone().expand(BUILDING_BOUNDS_MARGIN);

            // Get mobs in range
            List<LivingEntity> meleeMobs = getNearbyMobs(data.getCenter(), MELEE_AGGRO_RANGE);
            List<LivingEntity> rangedMobs = getNearbyMobs(data.getCenter(), RANGED_AGGRO_RANGE);

            long currentTime = System.currentTimeMillis();

            // Process melee mobs
            for (LivingEntity mob : meleeMobs) {
                if (!isMeleeMob(mob)) continue;
                handleMeleeMob(mob, data, buildingBounds, buildingEntity, currentTime);
            }

            // Process ranged mobs
            for (LivingEntity mob : rangedMobs) {
                if (!isRangedMob(mob)) continue;
                handleRangedMob(mob, data, buildingEntity, currentTime);
            }
        }
    }

    private void handleMeleeMob(LivingEntity mob, BuildingHealthData data,
                                BoundingBox buildingBounds, LivingEntity buildingEntity,
                                long currentTime) {
        // 修复：添加类型检查（移除无效的类型转换）
        if (!(mob instanceof Mob mobEntity)) return;

        Player nearbyPlayer = findNearbyPlayer(mob.getLocation(), PLAYER_PRIORITY_RANGE);

        // Player priority system
        if (nearbyPlayer != null) {
            // Can we see and attack player?
            if (canAttackPlayer(mobEntity, nearbyPlayer)) {
                mobEntity.setTarget(nearbyPlayer);
                lastPlayerSightTimes.put(mob.getUniqueId(), currentTime);
                return;
            }

            // Can't attack player - try building
            if (currentTime - lastPlayerSightTimes.getOrDefault(mob.getUniqueId(), 0L) > PLAYER_TARGET_TIMEOUT) {
                mobEntity.setTarget(buildingEntity);
            }
        } else {
            mobEntity.setTarget(buildingEntity);
        }

        // If mob is targeting building and in range, attack
        if (mobEntity.getTarget() != null && mobEntity.getTarget().equals(buildingEntity)) {
            if (isNearBuildingSurface(mob.getLocation(), buildingBounds)) {
                attemptMeleeAttack(mob, data, currentTime);

                // 新增劫掠兽咆哮攻击
                if (mob instanceof Ravager) {
                    if (Math.random() < 0.25) { // 25%几率触发咆哮
                        attemptRavagerRoar((Ravager) mob, data);
                    }
                }
            }
        }
    }

    // 新增劫掠兽咆哮攻击方法
    private void attemptRavagerRoar(Ravager ravager, BuildingHealthData data) {
        // 播放咆哮动画和音效
        ravager.getWorld().playSound(ravager.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2.0f, 1.0f);
        ravager.getWorld().spawnParticle(Particle.EXPLOSION,
                ravager.getLocation().add(0, 1, 0),
                3, 1, 1, 1, 0.1);

        // 计算咆哮伤害（无视护甲）
        double roarDamage = calculateMobDamage(ravager) * 1.5;

        // 直接应用伤害（穿透护甲）
        data.applyDamage(roarDamage);

        // 特效
        Location particleLoc = data.getCenter().add(0, 1, 0);
        ravager.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, particleLoc, 15, 0.5, 0.5, 0.5, 0.1);

        // 检查建筑是否被摧毁
        if (data.getHealth() <= 0) {
            healthSystem.getCollapseSystem().collapseBuilding(data);
        }
    }

    private void handleRangedMob(LivingEntity mob, BuildingHealthData data,
                                 LivingEntity buildingEntity, long currentTime) {
        if (!(mob instanceof Mob mobEntity)) return;

        // 1. 优先搜索玩家目标
        Player nearbyPlayer = findNearbyPlayer(mob.getLocation(), PLAYER_PRIORITY_RANGE);

        // 玩家优先级系统
        if (nearbyPlayer != null) {
            // 检查是否可以攻击玩家（视线和距离）
            if (canAttackPlayer(mobEntity, nearbyPlayer)) {
                mobEntity.setTarget(nearbyPlayer);
                lastPlayerSightTimes.put(mob.getUniqueId(), currentTime);
                return;
            }

            // 记录无法攻击玩家的开始时间
            if (!playerTargetFailStartTimes.containsKey(mob.getUniqueId())) {
                playerTargetFailStartTimes.put(mob.getUniqueId(), currentTime);
            }

            // 检查是否超时（5秒）
            if (currentTime - playerTargetFailStartTimes.get(mob.getUniqueId()) > PLAYER_TARGET_TIMEOUT) {
                // 超时后攻击建筑
                mobEntity.setTarget(buildingEntity);
                playerTargetFailStartTimes.remove(mob.getUniqueId());
            }
        } else {
            // 没有玩家在范围内 - 攻击建筑
            mobEntity.setTarget(buildingEntity);
            playerTargetFailStartTimes.remove(mob.getUniqueId());
        }

        // 如果怪物正在攻击建筑
        if (mobEntity.getTarget() != null && mobEntity.getTarget().equals(buildingEntity)) {
            double distance = mob.getLocation().distance(data.getCenter());
            double rangedRange = getRangedRange(mob, data);

            // 新增：调整距离逻辑
            if (distance > MAX_RANGED_DISTANCE) {
                // 太远，尝试靠近
                Vector toBuilding = data.getCenter().toVector().subtract(mob.getLocation().toVector()).normalize();
                mobEntity.teleport(mob.getLocation().add(toBuilding.multiply(0.5)));
            }
            else if (distance < MIN_RANGED_DISTANCE) {
                // 太近，尝试后退
                Vector awayDirection = mob.getLocation().toVector().subtract(data.getCenter().toVector()).normalize();
                mobEntity.teleport(mob.getLocation().add(awayDirection.multiply(MOVE_AWAY_SPEED)));
            }
            else if (distance <= rangedRange) {
                attemptRangedAttack(mob, data, buildingEntity, currentTime);
            }
        }
    }

    public boolean canShooterAttackNow(LivingEntity shooter) {
        Long lastAttack = lastAttackTimes.get(shooter.getUniqueId());
        if (lastAttack == null) return true;

        long cooldown = (long) (getAttackCooldown(shooter) * 1000);
        return System.currentTimeMillis() - lastAttack >= cooldown;
    }

    private Player findNearbyPlayer(Location loc, double radius) {
        Player nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (Player player : loc.getWorld().getPlayers()) {
            // 忽略创造模式和观察者模式的玩家
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distance(loc);
            if (distance <= radius && distance < minDistance) {
                minDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    // 新增：判断是否可以攻击玩家
    private boolean canAttackPlayer(Mob mob, Player player) {
        return mob.hasLineOfSight(player) &&
                mob.getLocation().distance(player.getLocation()) <= mob.getAttribute(Attribute.FOLLOW_RANGE).getValue();
    }

    private boolean isMeleeMob(LivingEntity mob) {
        return mob instanceof Zombie ||
                mob instanceof Piglin ||
                mob instanceof PiglinBrute ||
                mob instanceof Vindicator ||
                mob instanceof Ravager || // 添加劫掠兽
                mob instanceof Husk ||
                mob instanceof Drowned ||
                mob instanceof Spider ||
                mob instanceof CaveSpider ||
                mob instanceof Creeper;
    }

    private boolean isRangedMob(LivingEntity mob) {
        return mob instanceof Skeleton ||
                mob instanceof Witch ||
                mob instanceof Blaze ||
                mob instanceof Evoker ||
                mob instanceof Pillager ||
                mob instanceof Ghast;
    }

    private boolean isNearBuildingSurface(Location mobLoc, BoundingBox buildingBounds) {
        return calculateDistanceToBounds(mobLoc, buildingBounds) <= MELEE_ATTACK_RANGE;
    }

    private void attemptMeleeAttack(LivingEntity mob, BuildingHealthData data, long currentTime) {
        // Attack cooldown check
        UUID mobId = mob.getUniqueId();
        Long lastAttack = lastAttackTimes.get(mobId);
        if (lastAttack != null && currentTime - lastAttack < getAttackCooldown(mob) * 1000) {
            return;
        }

        mob.swingMainHand();
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.0f, 1.0f);

        // Special handling for creepers
        if (mob instanceof Creeper) {
            handleCreeperAttack(mob, data);
            lastAttackTimes.put(mobId, currentTime);
            return;
        }

        // Calculate damage
        double rawDamage = calculateMobDamage(mob);
        double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
        data.applyDamage(actualDamage);

        // Damage effects
        Location particleLoc = data.getCenter().add(0, 1, 0);
        mob.getWorld().spawnParticle(
                Particle.DAMAGE_INDICATOR,
                particleLoc,
                10,
                0.5, 0.5, 0.5,
                0.1
        );

        // Update last attack time
        lastAttackTimes.put(mobId, currentTime);

        // Check for building destruction
        if (data.getHealth() <= 0) {
            healthSystem.getCollapseSystem().collapseBuilding(data);
        }
    }

    private void attemptRangedAttack(LivingEntity mob, BuildingHealthData data,
                                     LivingEntity buildingEntity, long currentTime) {
        // Attack cooldown check
        UUID mobId = mob.getUniqueId();
        Long lastAttack = lastAttackTimes.get(mobId);
        if (lastAttack != null && currentTime - lastAttack < getAttackCooldown(mob) * 1000) {
            return;
        }

        // Face the building
        Vector direction = buildingEntity.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize();
        Location mobLoc = mob.getLocation();
        mobLoc.setDirection(direction);
        mob.teleport(mobLoc);

        // Special attacks
        if (mob instanceof Witch) {
            handleWitchAttack((Witch) mob, data, buildingEntity);
        } else if (mob instanceof Skeleton) {
            handleSkeletonAttack(mob, direction);
        } else if (mob instanceof Ghast) {
            handleGhastAttack(mob, direction, data);
        } else if (mob instanceof Evoker) {
            handleEvokerAttack(mob, data);
        }

        // Update last attack time
        lastAttackTimes.put(mobId, currentTime);
    }

    private void handleWitchAttack(Witch witch, BuildingHealthData data, LivingEntity buildingEntity) {
        ThrownPotion potion = witch.launchProjectile(ThrownPotion.class);
        potion.setShooter(witch);

        ItemStack potionItem = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 1), true);
        meta.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 8 * 20, 1), true);
        potionItem.setItemMeta(meta);
        potion.setItem(potionItem);

        Vector direction = buildingEntity.getLocation().toVector().subtract(witch.getLocation().toVector()).normalize();
        potion.setVelocity(direction.multiply(1.2));

        PersistentDataContainer pdc = potion.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, "witch_damage"),
                PersistentDataType.DOUBLE,
                calculateMobDamage(witch));
        pdc.set(new NamespacedKey(plugin, "is_witch_potion"),
                PersistentDataType.BYTE,
                (byte) 1);

        witch.getWorld().playSound(witch.getLocation(), Sound.ENTITY_WITCH_THROW, 1.0f, 1.0f);
    }

    private void handleSkeletonAttack(LivingEntity mob, Vector direction) {
        Arrow arrow = mob.launchProjectile(Arrow.class);
        arrow.setShooter(mob);
        arrow.setVelocity(direction.multiply(1.5));
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1.0f, 1.0f);

        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, "building_damage"),
                PersistentDataType.DOUBLE,
                calculateMobDamage(mob));
    }

    private void handleGhastAttack(LivingEntity mob, Vector direction, BuildingHealthData data) {
        Location adjustedLoc = mob.getLocation().clone().add(0, 5, 0);
        LargeFireball fireball = ((Ghast) mob).launchProjectile(LargeFireball.class);
        fireball.setDirection(direction);
        fireball.setShooter(mob);
        mob.getWorld().playSound(adjustedLoc, Sound.ENTITY_GHAST_SHOOT, 1.0f, 1.0f);

        PersistentDataContainer pdc = fireball.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, "building_damage"),
                PersistentDataType.DOUBLE,
                calculateMobDamage(mob) * 2);

        if (data != null) {
            pdc.set(new NamespacedKey(plugin, "target_building"),
                    PersistentDataType.STRING,
                    data.getBuildingId().toString());
        }
    }

    private void handleEvokerAttack(LivingEntity mob, BuildingHealthData data) {
        // Evokers attack with fangs
        mob.getWorld().spawn(mob.getLocation(), EvokerFangs.class, fangs -> {
            PersistentDataContainer pdc = fangs.getPersistentDataContainer();
            pdc.set(new NamespacedKey(plugin, "building_damage"),
                    PersistentDataType.DOUBLE,
                    calculateMobDamage(mob));

            if (data != null) {
                pdc.set(new NamespacedKey(plugin, "target_building"),
                        PersistentDataType.STRING,
                        data.getBuildingId().toString());
            }
        });
    }

    private void handleCreeperAttack(LivingEntity mob, BuildingHealthData data) {
        Creeper creeper = (Creeper) mob;
        if (creeper.isIgnited()) return;

        // Check if within explosion range
        double distance = calculateExactDistanceToSurface(mob.getLocation(), data);
        if (distance > CREEPER_EXPLOSION_RANGE) return;

        // Prepare explosion
        creeper.getWorld().playSound(creeper.getLocation(),
                Sound.ENTITY_CREEPER_PRIMED,
                1.0f,
                1.0f);
        creeper.getWorld().spawnParticle(Particle.SMOKE,
                creeper.getLocation(),
                10,
                0.5, 0.5, 0.5,
                0.1);

        // Custom explosion logic (no block damage)
        new BukkitRunnable() {
            @Override
            public void run() {
                double damage = calculateMobDamage(mob) * 3; // Higher damage for buildings
                data.applyDamage(damage);

                // Explosion effect
                Location loc = creeper.getLocation();
                loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1);
                loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

                // Remove creeper
                creeper.remove();

                // Check building destruction
                if (data.getHealth() <= 0) {
                    healthSystem.getCollapseSystem().collapseBuilding(data);
                }
            }
        }.runTaskLater(plugin, 20); // 1 second delay
    }

    public void handleFangsDamage(EvokerFangs fangs) {
        PersistentDataContainer pdc = fangs.getPersistentDataContainer();
        if (pdc.has(new NamespacedKey(plugin, "building_damage"), PersistentDataType.DOUBLE)) {
            double rawDamage = pdc.get(new NamespacedKey(plugin, "building_damage"), PersistentDataType.DOUBLE);
            Location fangLoc = fangs.getLocation();

            // Get building data
            BuildingHealthData data = healthSystem.getBuildingAtLocation(fangLoc);
            if (data == null || data.isCollapsing() || data.getHealth() <= 0) return;

            // Apply damage
            double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
            data.applyDamage(actualDamage);
            fangs.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    fangs.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);

            if (data.getHealth() <= 0) {
                healthSystem.getCollapseSystem().collapseBuilding(data);
            }
        }
    }

    public void handleWitchPotionImpact(ThrownPotion potion, Location hitLocation) {
        double rawDamage = 0.0;
        if (potion.getPersistentDataContainer().has(
                new NamespacedKey(plugin, "witch_damage"), PersistentDataType.DOUBLE)) {
            rawDamage = potion.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "witch_damage"), PersistentDataType.DOUBLE);
        }

        // Apply building damage
        BuildingHealthData data = healthSystem.getBuildingAtLocation(hitLocation);
        if (data != null) {
            double actualDamage = healthSystem.calculateActualDamage(rawDamage, data.getArmor());
            data.applyDamage(actualDamage);

            // Create explosion effect
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

            // Trigger collapse
            if (data.getHealth() <= 0) {
                healthSystem.getCollapseSystem().collapseBuilding(data);
            }
        }

        // Apply effects to entities
        applyPotionEffects(potion, hitLocation);
    }

    // Apply potion effects with special handling for undead mobs
    public void applyPotionEffects(ThrownPotion potion, Location hitLocation) {
        for (LivingEntity entity : potion.getWorld().getLivingEntities()) {
            if (entity.getLocation().distance(hitLocation) > 4.0) continue;

            // Special handling for undead mobs
            if (isUndead(entity)) {
                applyReversedEffects(entity);
            } else {
                applyNormalEffects(entity);
            }
        }
    }

    private boolean isUndead(LivingEntity entity) {
        return entity instanceof Zombie ||
                entity instanceof Skeleton ||
                entity instanceof WitherSkeleton ||
                entity instanceof ZombieVillager ||
                entity instanceof ZombieHorse ||
                entity instanceof Drowned ||
                entity instanceof Phantom ||
                entity instanceof Wither;
    }

    private void applyReversedEffects(LivingEntity entity) {
        // 修复：使用正确的药水效果
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 200, 0)); // INCREASE_DAMAGE 改为 INCREASE_DAMAGE

        entity.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION,
                100,
                0
        ));
    }

    private void applyNormalEffects(LivingEntity entity) {
        // Instant damage
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 1));

        // Slowness
        entity.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                8 * 20,
                1
        ));
    }

    @EventHandler
    public void onVexTarget(EntityTargetEvent event) {
        // Prevent vexes from targeting buildings
        if (event.getEntity() instanceof Vex && healthSystem.isBuildingEntity((LivingEntity) event.getTarget())) {
            event.setCancelled(true);
        }
    }

    private double calculateExactDistanceToSurface(Location location, BuildingHealthData data) {
        BoundingBox exactBounds = data.getExactBoundingBox();
        Vector pos = location.toVector();
        return Math.min(
                Math.min(
                        Math.min(Math.abs(pos.getX() - exactBounds.getMinX()), Math.abs(pos.getX() - exactBounds.getMaxX())),
                        Math.min(Math.abs(pos.getZ() - exactBounds.getMinZ()), Math.abs(pos.getZ() - exactBounds.getMaxZ()))
                ),
                Math.min(Math.abs(pos.getY() - exactBounds.getMinY()), Math.abs(pos.getY() - exactBounds.getMaxY()))
        );
    }

    private double getRangedRange(LivingEntity mob, BuildingHealthData data) {
        // Different ranges for different mob types
        if (mob instanceof Ghast) return 30.0;
        if (mob instanceof Witch) return 15.0;
        if (mob instanceof Skeleton) return 20.0;
        if (mob instanceof Pillager) return 25.0;
        return RANGED_ATTACK_RANGE;
    }

    private double calculateDistanceToBounds(Location location, BoundingBox bounds) {
        if (bounds == null) return Double.MAX_VALUE;

        Vector pos = location.toVector();
        double dx = Math.max(Math.max(bounds.getMinX() - pos.getX(), 0), pos.getX() - bounds.getMaxX());
        double dy = Math.max(Math.max(bounds.getMinY() - pos.getY(), 0), pos.getY() - bounds.getMaxY());
        double dz = Math.max(Math.max(bounds.getMinZ() - pos.getZ(), 0), pos.getZ() - bounds.getMaxZ());

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double getAttackCooldown(LivingEntity mob) {
        // Different attack speeds for different mob types
        if (mob instanceof Ghast) return 5.0;
        if (mob instanceof Witch) return 3.0;
        if (mob instanceof Skeleton) return 2.0;
        if (mob instanceof Pillager) return 3.5;
        if (mob instanceof Creeper) return 2.0;
        return 1.5;
    }

    private double calculateMobDamage(LivingEntity mob) {
        double baseDamage = 2.0;

        // Damage based on mob type
        if (mob instanceof Zombie) baseDamage = 3.0;
        else if (mob instanceof Skeleton) baseDamage = 2.5;
        else if (mob instanceof Creeper) baseDamage = 6.0;
        else if (mob instanceof Ravager) baseDamage = 7.0; // 劫掠兽基础伤害
        else if (mob instanceof Ghast) baseDamage = 5.0;
        else if (mob instanceof Evoker) baseDamage = 4.0;
        else if (mob instanceof Vindicator) baseDamage = 4.0;
        else if (mob instanceof Piglin || mob instanceof PiglinBrute) baseDamage = 3.5;
        else if (mob instanceof Husk) baseDamage = 3.0;
        else if (mob instanceof Drowned) baseDamage = 3.0;

        // Equipment modifiers
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            ItemStack handItem = equipment.getItemInMainHand();
            if (handItem != null) {
                switch (handItem.getType()) {
                    case DIAMOND_SWORD: baseDamage += 3.0; break;
                    case IRON_SWORD: baseDamage += 2.0; break;
                    case NETHERITE_SWORD: baseDamage += 4.0; break;
                    case STONE_SWORD: baseDamage += 1.0; break;
                    case GOLDEN_SWORD: baseDamage += 0.5; break;
                    case BOW: case CROSSBOW: baseDamage += 1.5; break;
                    case TRIDENT: baseDamage += 2.5; break;
                    case WOODEN_AXE: case STONE_AXE: case IRON_AXE:
                    case DIAMOND_AXE: case NETHERITE_AXE:
                        baseDamage += (mob instanceof Vindicator || mob instanceof PiglinBrute) ? 3.0 : 2.0;
                        break;
                }
            }
        }

        // Difficulty modifiers
        switch (mob.getWorld().getDifficulty()) {
            case HARD: baseDamage *= 1.3; break;
            case NORMAL: baseDamage *= 1.1; break;
        }

        return baseDamage;
    }
}