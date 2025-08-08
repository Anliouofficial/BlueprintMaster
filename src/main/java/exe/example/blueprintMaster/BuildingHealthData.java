package exe.example.blueprintMaster;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

public class BuildingHealthData {
    private boolean attractMonsters;
    private final UUID buildingId;
    private final boolean explosionDamage;
    private final Location bottomCenter;
    private final int width;
    private final int height;
    private final int length;
    private final int rotation;
    private double maxHealth; // 改为非final
    private double health;
    private double armor;
    private boolean generating;
    private boolean collapsing;
    private UUID entityId;
    private UUID ownerId;
    private final BoundingBox boundingBox;

    public void showHealthBar(Player player) {
        if (!player.getWorld().equals(getCenter().getWorld())) return;
        Location center = getCenter();
        double healthPercent = health / maxHealth;

        Particle.DustOptions[] colors = {
                new Particle.DustOptions(Color.RED, 1.5f),
                new Particle.DustOptions(Color.YELLOW, 1.5f),
                new Particle.DustOptions(Color.GREEN, 1.5f)
        };

        for (int i = 0; i < 12; i++) {
            double xOffset = (i - 6) * 0.25;
            Location point = center.clone().add(xOffset, height + 0.5, 0);

            int colorIndex = (i < healthPercent * 12) ?
                    (healthPercent > 0.5 ? 2 : 1) : 0;

            player.spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    colors[colorIndex]
            );
        }
    }

    public BoundingBox getExactBoundingBox() {
        return RotationUtil.calculateExactBoundingBox(
                bottomCenter,
                width,
                height,
                length,
                rotation
        );
    }

    public BuildingHealthData(UUID buildingId, Location bottomCenter,
                              int width, int height, int length,
                              int rotation, double health, double maxHealth, double armor,
                              boolean explosionDamage, // 确保该参数存在
                              boolean generating,
                              boolean attractMonsters) {
        this.buildingId = buildingId;
        this.bottomCenter = bottomCenter;
        this.width = width;
        this.height = height;
        this.length = length;
        this.rotation = RotationUtil.normalizeRotation(rotation);
        this.maxHealth = maxHealth;
        this.health = health;
        this.armor = armor;
        this.explosionDamage = explosionDamage; // 修复：只赋值一次
        this.generating = generating;
        this.collapsing = false;
        this.boundingBox = getExactBoundingBox();
        this.attractMonsters = attractMonsters;
    }

    public boolean shouldAttractMonsters() {
        return attractMonsters;
    }
    public boolean isExplosionDamage() {
        return explosionDamage;
    }
    public UUID getBuildingId() { return buildingId; }
    public Location getCenter() { return bottomCenter.clone().add(0, height / 2.0, 0); }
    public Location getBottomCenter() { return bottomCenter; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getLength() { return length; }
    public int getRotation() { return rotation; }
    public double getHealth() { return health; }
    public double getMaxHealth() { return maxHealth; }
    public double getArmor() { return armor; }
    public boolean isGenerating() { return generating; }
    public boolean isCollapsing() { return collapsing; }
    public BoundingBox getBoundingBox() {
        return boundingBox;
    }
    public UUID getEntityId() { return entityId; }
    public UUID getOwnerId() { return ownerId; }

    public void setHealth(double health) { this.health = Math.max(0, health); }
    public void setMaxHealth(double maxHealth) { this.maxHealth = maxHealth; }
    public void setGenerating(boolean generating) { this.generating = generating; }
    public void setCollapsing(boolean collapsing) { this.collapsing = collapsing; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public void applyDamage(double damage) {
        this.health = Math.max(0, this.health - damage);
    }
}