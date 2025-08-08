package exe.example.blueprintMaster;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class BuildingAggroSystem {
    private final Map<UUID, Map<UUID, Integer>> buildingAggro = new HashMap<>();
    private final JavaPlugin plugin;
    private final BuildingHealthSystem healthSystem;
    private final Map<UUID, LivingEntity> buildingEntities;

    public BuildingAggroSystem(JavaPlugin plugin, BuildingHealthSystem healthSystem,
                               Map<UUID, LivingEntity> buildingEntities) {
        this.plugin = plugin;
        this.healthSystem = healthSystem;
        this.buildingEntities = buildingEntities;
        startAggroTask();
    }

    public void addAggro(UUID buildingId, LivingEntity entity, int priority) {
        // 关键修复：过滤中立生物
        if (!isHostileMob(entity)) return;

        Map<UUID, Integer> aggroMap = buildingAggro.computeIfAbsent(buildingId, k -> new HashMap<>());
        aggroMap.put(entity.getUniqueId(), priority);
    }
    private boolean isHostileMob(LivingEntity entity) {
        return entity instanceof Monster ||
                entity instanceof Ghast ||
                entity instanceof Slime ||
                entity instanceof Phantom;
    }
    public void addAggro(UUID buildingId, LivingEntity entity) {
        addAggro(buildingId, entity, 1);
    }

    private void startAggroTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (BuildingHealthData data : healthSystem.getBuildings().values()) {
                    if (data.isGenerating() || data.getHealth() <= 0 || data.isCollapsing()) continue;

                    UUID buildingId = data.getBuildingId();
                    Map<UUID, Integer> aggroMap = buildingAggro.getOrDefault(buildingId, Collections.emptyMap());
                    if (aggroMap.isEmpty()) continue;

                    LivingEntity buildingEntity = buildingEntities.get(buildingId);
                    if (buildingEntity == null) continue;

                    List<UUID> sortedMobs = aggroMap.entrySet().stream()
                            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList());

                    for (UUID mobId : sortedMobs) {
                        Entity entity = Bukkit.getEntity(mobId);

                        if (entity == null || entity.isDead() || !entity.isValid()) {
                            aggroMap.remove(mobId);
                            continue;
                        }

                        if (entity instanceof Mob mob) {
                            if (mob.getLocation().distance(data.getCenter()) > 50) {
                                aggroMap.remove(mobId);
                                continue;
                            }

                            mob.setTarget(buildingEntity);
                            mob.setAware(true);
                            mob.setNoDamageTicks(0);

                            mob.getWorld().spawnParticle(
                                    Particle.HEART,
                                    mob.getEyeLocation(),
                                    3
                            );
                        }
                    }
                }

                buildingAggro.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            }
        }.runTaskTimer(plugin, 0, 20);
    }

    public void removeBuildingAggro(UUID buildingId) {
        Map<UUID, Integer> aggroMap = buildingAggro.get(buildingId);
        if (aggroMap != null) {
            for (UUID mobId : aggroMap.keySet()) {
                Entity entity = Bukkit.getEntity(mobId);
                if (entity instanceof Mob mob) {
                    mob.setTarget(null);
                }
            }
        }
        buildingAggro.remove(buildingId);
    }
}