// 文档3: Selection.java
package exe.example.blueprintMaster;

import org.bukkit.Location;

public class Selection {
    private Location pos1;
    private Location pos2;

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public boolean isValid() {
        return pos1 != null && pos2 != null &&
                pos1.getWorld().equals(pos2.getWorld());
    }

    public Location getMinCorner() {
        return new Location(
                pos1.getWorld(),
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
    }

    public int getWidth() {
        return (int) Math.abs(pos1.getX() - pos2.getX()) + 1;
    }

    public int getHeight() {
        return (int) Math.abs(pos1.getY() - pos2.getY()) + 1;
    }

    public int getLength() {
        return (int) Math.abs(pos1.getZ() - pos2.getZ()) + 1;
    }

    public int getVolume() {
        return getWidth() * getHeight() * getLength();
    }
}