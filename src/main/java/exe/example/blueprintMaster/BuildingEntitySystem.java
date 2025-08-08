package exe.example.blueprintMaster;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingEntitySystem {
    private final JavaPlugin plugin;
    private final Map<UUID, LivingEntity> buildingEntities; // 移除初始化
    private final NamespacedKey buildingIdKey;

    public BuildingEntitySystem(JavaPlugin plugin) {
        this.plugin = plugin;
        this.buildingEntities = new HashMap<>(); // 在构造函数中初始化
        this.buildingIdKey = new NamespacedKey(plugin, "building_id");
    }

    public LivingEntity createBuildingEntity(Location center, int width, int height, int length,
                                             double health, double maxHealth, int rotation, UUID buildingId) {
        center = RotationUtil.alignToGrid(center);
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
        return entity;
    }

    public boolean isBuildingEntity(LivingEntity entity) {
        if (entity == null) return false;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(buildingIdKey, PersistentDataType.STRING);
    }

    public Map<UUID, LivingEntity> getBuildingEntities() {
        return buildingEntities;
    }

}