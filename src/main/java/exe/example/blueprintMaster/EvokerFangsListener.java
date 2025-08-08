package exe.example.blueprintMaster;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class EvokerFangsListener implements Listener {
    private final BuildingMonsterAttackSystem attackSystem;
    private static final NamespacedKey OWNER_KEY = new NamespacedKey("template_manager", "owner_id");

    public EvokerFangsListener(BuildingMonsterAttackSystem attackSystem) {
        this.attackSystem = attackSystem;
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EvokerFangs fangs) {
            // 设置尖刺的主人（防止伤害唤魔师自己）
            if (fangs.getOwner() instanceof Evoker) {
                PersistentDataContainer pdc = fangs.getPersistentDataContainer();
                pdc.set(OWNER_KEY, PersistentDataType.STRING, fangs.getOwner().getUniqueId().toString());
            }
            attackSystem.handleFangsDamage(fangs);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof EvokerFangs fangs) {
            // 防止尖刺伤害自己的主人（唤魔师）
            PersistentDataContainer pdc = fangs.getPersistentDataContainer();
            if (pdc.has(OWNER_KEY, PersistentDataType.STRING)) {
                String ownerId = pdc.get(OWNER_KEY, PersistentDataType.STRING);
                if (event.getEntity().getUniqueId().toString().equals(ownerId)) {
                    event.setCancelled(true);
                    return;
                }
            }

            attackSystem.handleFangsDamage(fangs);
        }
    }
}