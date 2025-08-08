package exe.example.blueprintMaster;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class BuildingDestoryEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final UUID buildingId;
    private final Location location;

    public BuildingDestoryEvent(UUID buildingId, Location location) {
        this.buildingId = buildingId;
        this.location = location;
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public Location getLocation() {
        return location;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}