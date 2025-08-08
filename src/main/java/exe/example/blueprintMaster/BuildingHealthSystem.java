package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingHealthSystem implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, BuildingHealthData> buildings = new HashMap<>();
    private final Map<UUID, Long> damageTimestamps = new HashMap<>();
    private final Map<UUID, LivingEntity> buildingEntities = new HashMap<>();
    private final Map<UUID, BukkitTask> monsterAttractionTasks = new HashMap<>();

    private BuildingAggroSystem aggroSystem;
    private BuildingCollapseSystem collapseSystem;
    private BuildingMonsterAttackSystem monsterAttackSystem;
    private BuildingEntitySystem entitySystem;

    private final NamespacedKey buildingIdKey;
    private final NamespacedKey buildingRotationKey;

    public BuildingHealthSystem(JavaPlugin plugin) {
        this.plugin = plugin;
        this.buildingIdKey = new NamespacedKey(plugin, "building_id");
        this.buildingRotationKey = new NamespacedKey(plugin, "building_rotation");

        this.aggroSystem = new BuildingAggroSystem(plugin, this, buildingEntities);
        this.monsterAttackSystem = new BuildingMonsterAttackSystem(plugin, this, buildingEntities, aggroSystem);
        this.collapseSystem = new BuildingCollapseSystem(
                plugin,
                buildings,
                buildingEntities,
                this,
                monsterAttackSystem
        );
        this.entitySystem = new BuildingEntitySystem(plugin);
        BuildingDamageSystem damageSystem = new BuildingDamageSystem(
                plugin,
                this.aggroSystem,
                this.entitySystem,
                this,
                this.monsterAttackSystem
        );

        plugin.getServer().getPluginManager().registerEvents(damageSystem, plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void applyDamage(UUID buildingId, double damage) {
        BuildingHealthData data = buildings.get(buildingId);
        if (data == null || data.isCollapsing()) return;

        double newHealth = Math.max(0, data.getHealth() - damage);
        data.setHealth(newHealth);

        if (newHealth <= 0 && !data.isCollapsing()) {
            getCollapseSystem().collapseBuilding(data);
        }
    }

    public BuildingAggroSystem getAggroSystem() {
        return this.aggroSystem;
    }

    public void completelyRemoveBuilding(UUID buildingId) {
        BuildingHealthData data = buildings.get(buildingId);
        if (data == null) return;

        UUID entityUUID = null;
        LivingEntity entity = buildingEntities.get(buildingId);
        if (entity != null) {
            entityUUID = entity.getUniqueId();
            entity.remove();
            buildingEntities.remove(buildingId);
        }

        if (plugin instanceof TemplateManagerPlugin) {
            ((TemplateManagerPlugin) plugin).removePermanentOutline(data.getBottomCenter());
        }

        buildings.remove(buildingId);
        damageTimestamps.remove(buildingId);
        aggroSystem.removeBuildingAggro(buildingId);

        for (Entity mob : data.getCenter().getWorld().getEntities()) {
            if (mob instanceof Mob) {
                Mob mobEntity = (Mob) mob;
                LivingEntity target = mobEntity.getTarget();
                if (target != null) {
                    if ((entityUUID != null && target.getUniqueId().equals(entityUUID)) ||
                            target.getLocation().distance(data.getCenter()) < 1.0) {
                        mobEntity.setTarget(null);
                    }
                }
            }
        }
    }

    public Map<UUID, BuildingHealthData> getBuildings() {
        return buildings;
    }

    public Map<UUID, Long> getDamageTimestamps() {
        return damageTimestamps;
    }

    public void registerBuilding(UUID buildingId, UUID ownerId, Location bottomCenter,
                                 int width, int height, int length, int rotation,
                                 boolean isGenerating, double maxHealth, double armor,
                                 boolean explosionDamage, boolean attractMonsters) {

        // 新增: 识别水晶实体
        Entity entity = Bukkit.getEntity(buildingId);
        if (entity instanceof ArmorStand) {
            ArmorStand armorStand = (ArmorStand) entity;
            if (armorStand.getCustomName() != null && armorStand.getCustomName().equals("CRYSTAL")) {
                // 水晶特殊属性
                attractMonsters = true;
                explosionDamage = false;
                armor = 0;
            }
        }

        BuildingHealthData data = new BuildingHealthData(
                buildingId,
                bottomCenter,
                width,
                height,
                length,
                rotation,
                maxHealth,
                maxHealth,
                armor,
                explosionDamage,
                isGenerating,
                attractMonsters
        );
        if (attractMonsters) {
            startMonsterAttractionEffect(data);
        }

        LivingEntity buildingEntity = createBuildingEntity(
                bottomCenter,
                buildingId,
                rotation
        );
        buildingEntities.put(buildingId, buildingEntity);
        data.setEntityId(buildingEntity.getUniqueId());
        data.setOwnerId(ownerId);

        buildings.put(buildingId, data);
    }

    private void startMonsterAttractionEffect(BuildingHealthData data) {
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (data.isCollapsing() || data.getHealth() <= 0) {
                    cancelTask(data.getBuildingId());
                    return;
                }

                Location center = data.getCenter().add(0, data.getHeight() + 2, 0);
                drawMonsterAttractionRing(center);
            }
        }.runTaskTimer(plugin, 0, 20);

        monsterAttractionTasks.put(data.getBuildingId(), task);
    }

    private void drawMonsterAttractionRing(Location center) {
        World world = center.getWorld();
        double radius = 1.5;
        int points = 16;

        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = radius * Math.cos(angle);
            double z = radius * Math.sin(angle);
            Location point = center.clone().add(x, 0, z);

            world.spawnParticle(Particle.SHRIEK, point, 1,
                    new Particle.DustOptions(Color.RED, 1.5f));
        }
    }

    private void cancelTask(UUID buildingId) {
        BukkitTask task = monsterAttractionTasks.remove(buildingId);
        if (task != null) {
            task.cancel();
        }
    }

    public void buildingCompleted(UUID buildingId) {
        BuildingHealthData data = buildings.get(buildingId);
        if (data != null) {
            data.setGenerating(false);
            data.setHealth(data.getMaxHealth());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)) return;

        ArmorStand armorStand = (ArmorStand) event.getEntity();
        if (!isBuildingEntity(armorStand)) {
            return;
        }

        BuildingHealthData data = getBuildingData(armorStand);
        if (data == null || data.isCollapsing()) {
            event.setCancelled(true);
            return;
        }
    }

    private double calculateActualDamage(Entity damager) {
        double baseDamage = 1.0;

        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof LivingEntity shooter) {
                return calculateMobDamage(shooter);
            }
            return 2.0;
        } else if (damager instanceof LivingEntity attacker) {
            return calculateMobDamage(attacker);
        } else if (damager instanceof Player) {
            return 3.0;
        }

        return baseDamage;
    }

    private double calculateMobDamage(LivingEntity mob) {
        double damage = 1.0;

        if (mob instanceof Zombie) {
            damage = 2.0;
        } else if (mob instanceof Skeleton) {
            damage = 2.0;
        } else if (mob instanceof Creeper) {
            damage = 3.0;
        }

        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            ItemStack handItem = equipment.getItemInMainHand();
            if (handItem != null) {
                switch (handItem.getType()) {
                    case DIAMOND_SWORD:
                        damage += 3.0;
                        break;
                    case IRON_SWORD:
                        damage += 2.0;
                        break;
                    case STONE_SWORD:
                        damage += 1.0;
                        break;
                    case BOW:
                    case CROSSBOW:
                        damage += 1.5;
                        break;
                }
            }
        }

        return damage;
    }

    public boolean isBuildingEntity(LivingEntity entity) {
        if (entity == null) return false;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(buildingIdKey, PersistentDataType.STRING);
    }

    public BuildingHealthData getBuildingData(LivingEntity entity) {
        if (!isBuildingEntity(entity)) return null;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        UUID buildingId = UUID.fromString(pdc.get(buildingIdKey, PersistentDataType.STRING));
        return buildings.get(buildingId);
    }

    public BuildingHealthData getBuildingAtLocation(Location loc) {
        for (BuildingHealthData data : buildings.values()) {
            if (data.getBoundingBox().contains(loc.toVector())) {
                return data;
            }
        }
        return null;
    }

    private LivingEntity createBuildingEntity(Location center, UUID buildingId, int rotation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("实体必须在主线程创建!");
        }

        ArmorStand entity = center.getWorld().spawn(center, ArmorStand.class);
        entity.setCollidable(true); // 允许碰撞
        entity.setInvulnerable(false);
        entity.setGravity(false);
        entity.setCustomNameVisible(false);
        entity.setCanPickupItems(false);
        entity.setCollidable(true); // 允许碰撞
        entity.setSilent(true);
        entity.setVisible(false); // 对玩家不可见
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.clear();
        }

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(buildingIdKey, PersistentDataType.STRING, buildingId.toString());
        pdc.set(buildingRotationKey, PersistentDataType.INTEGER, RotationUtil.normalizeRotation(rotation));

        return entity;
    }

    public void removeBuilding(Player player, UUID buildingId) {
        BuildingHealthData data = buildings.get(buildingId);
        if (data == null) {
            player.sendMessage(ChatColor.RED + "建筑不存在或已被删除");
            return;
        }

        if (!data.getOwnerId().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "您不是该建筑的所有者");
            return;
        }
        getCollapseSystem().collapseBuilding(data);
        player.playSound(data.getCenter(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        player.spawnParticle(Particle.CLOUD, data.getCenter(), 50, 1, 1, 1, 0.2);
    }

    public Map<UUID, LivingEntity> getBuildingEntities() {
        return buildingEntities;
    }

    public BuildingMonsterAttackSystem getMonsterAttackSystem() {
        return this.monsterAttackSystem;
    }

    public BuildingCollapseSystem getCollapseSystem() {
        return collapseSystem;
    }

    // 优化血条显示 - 移除player参数
    public void showHealthBar(BuildingHealthData data, boolean show, boolean temporary) {
        LivingEntity buildingEntity = buildingEntities.get(data.getBuildingId());
        if (buildingEntity == null) return;

        if (temporary || !buildingEntity.isCustomNameVisible()) {
            buildingEntity.setCustomName(ChatColor.RED + "♥ " +
                    String.format("%.1f/%.1f", data.getHealth(), data.getMaxHealth()));
            buildingEntity.setCustomNameVisible(true);
        }

        if (!temporary) return;

        Location displayLoc = data.getCenter().clone().add(0, 0.5, 0);
        double healthPercent = data.getHealth() / data.getMaxHealth();
        Particle.DustOptions[] dustOptions = {
                new Particle.DustOptions(Color.RED, 1.5f),
                new Particle.DustOptions(Color.YELLOW, 1.5f),
                new Particle.DustOptions(Color.GREEN, 1.5f)
        };

        for (int i = 0; i < 12; i++) {
            double xOffset = (i - 6) * 0.25;
            Location point = displayLoc.clone().add(xOffset, 0, 0);

            int colorIndex = i < (healthPercent * 12) ?
                    (healthPercent > 0.5 ? 2 : 1) : 0;

            displayLoc.getWorld().spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    dustOptions[colorIndex]
            );
        }
    }

    public void repairBuilding(BuildingHealthData data, double amount) {
        double newHealth = Math.min(data.getMaxHealth(), data.getHealth() + amount);
        data.setHealth(newHealth);
    }

    public double calculateActualDamage(double rawDamage, double armor) {
        double reduction = Math.min(armor * 0.01, 0.8);
        return rawDamage * (1 - reduction);
    }

    public void applyExplosionDamage(UUID buildingId, double damage) {
        BuildingHealthData data = buildings.get(buildingId);
        if (data == null || data.isCollapsing()) return;

        data.applyDamage(damage);

        Location center = data.getCenter();
        center.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, center, 20, 0.5, 0.5, 0.5, 0.1);

        if (data.getHealth() <= 0) {
            getCollapseSystem().collapseBuilding(data);
        }
    }
}