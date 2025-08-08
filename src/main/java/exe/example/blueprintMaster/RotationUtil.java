package exe.example.blueprintMaster;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class RotationUtil {
    public static int normalizeRotation(int rotation) {
        rotation = rotation % 360;
        if (rotation < 0) rotation += 360;
        return (rotation / 90) * 90;
    }
    // +++ 新增：精确距离计算方法 +++
    public static double calculateExactDistanceToSurface(Location location, BuildingHealthData data) {
        if (data == null) return Double.MAX_VALUE;

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
    public static int getRotatedWidth(int rotation, int width, int length) {
        rotation = normalizeRotation(rotation);
        if (rotation == 90 || rotation == 270) {
            return length;
        }
        return width;
    }

    public static int getRotatedLength(int rotation, int width, int length) {
        rotation = normalizeRotation(rotation);
        if (rotation == 90 || rotation == 270) {
            return width;
        }
        return length;
    }

    public static Vector getFrontVector(int rotation) {
        rotation = normalizeRotation(rotation);
        switch (rotation) {
            case 0: return new Vector(0, 0, 1);   // 正Z（南）
            case 90: return new Vector(-1, 0, 0);  // 负X（西）
            case 180: return new Vector(0, 0, -1); // 负Z（北）
            case 270: return new Vector(1, 0, 0);  // 正X（东）
            default: return new Vector(0, 0, 1);
        }
    }

    public static Vector rotateVector(Vector v, int rotation) {
        rotation = normalizeRotation(rotation);
        double angle = Math.toRadians(rotation);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        double newX = v.getX() * cos - v.getZ() * sin;
        double newZ = v.getX() * sin + v.getZ() * cos;

        // 添加微调因子解决浮点精度问题
        newX = Math.round(newX * 1000) / 1000.0;
        newZ = Math.round(newZ * 1000) / 1000.0;

        return new Vector(newX, v.getY(), newZ);
    }

    public static Vector[] calculateCorners(Location bottomCenter, int width, int length, int rotation) {
        Vector center = bottomCenter.toVector();
        double halfWidth = width / 2.0;
        double halfLength = length / 2.0;

        Vector[] corners = {
                new Vector(-halfWidth, 0, -halfLength),
                new Vector(halfWidth, 0, -halfLength),
                new Vector(halfWidth, 0, halfLength),
                new Vector(-halfWidth, 0, halfLength)
        };

        for (int i = 0; i < corners.length; i++) {
            corners[i] = rotateVector(corners[i], rotation).add(center);
        }

        return corners;
    }

    public static BoundingBox calculateExactBoundingBox(Location bottomCenter, int width, int height, int length, int rotation) {
        Vector[] corners = calculateCorners(bottomCenter, width, length, rotation);

        double minX = Double.MAX_VALUE;
        double minY = bottomCenter.getY();
        double minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = minY + height;
        double maxZ = Double.MIN_VALUE;

        for (Vector corner : corners) {
            minX = Math.min(minX, corner.getX());
            minZ = Math.min(minZ, corner.getZ());
            maxX = Math.max(maxX, corner.getX());
            maxZ = Math.max(maxZ, corner.getZ());
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Location calculateBottomCenter(Location placementLoc, int rotation, int rotatedWidth, int rotatedLength) {
        Location aligned = alignToGrid(placementLoc);

        // 根据旋转调整中心点偏移
        double xOffset = 0, zOffset = 0;
        switch (rotation) {
            case 90:
                zOffset = rotatedLength / 2.0;
                break;
            case 180:
                xOffset = rotatedWidth / 2.0;
                zOffset = rotatedLength / 2.0;
                break;
            case 270:
                xOffset = rotatedWidth / 2.0;
                break;
            default: // 0度
                zOffset = -rotatedLength / 2.0;
        }

        return aligned.add(xOffset, 0, zOffset);
    }

    public static Location alignToGrid(Location loc) {
        loc.setX(Math.floor(loc.getX()) + 0.5);
        loc.setZ(Math.floor(loc.getZ()) + 0.5);
        loc.setY(Math.floor(loc.getY()));
        return loc;
    }

    public static Location calculateBlockPosition(
            Location bottomCenter,
            int x, int y, int z,
            int width, int length,
            int rotation
    ) {
        // 使用中心点计算偏移
        double centerX = (width - 1) / 2.0;
        double centerZ = (length - 1) / 2.0;

        Vector offset = new Vector(x - centerX, y, z - centerZ);
        Vector rotated = rotateVector(offset, rotation);

        return bottomCenter.clone().add(rotated);
    }
    public static double calculateDistanceToBounds(Location location, BoundingBox bounds) {
        if (bounds == null) return Double.MAX_VALUE;

        Vector pos = location.toVector();
        double dx = Math.max(Math.max(bounds.getMinX() - pos.getX(), 0), pos.getX() - bounds.getMaxX());
        double dy = Math.max(Math.max(bounds.getMinY() - pos.getY(), 0), pos.getY() - bounds.getMaxY());
        double dz = Math.max(Math.max(bounds.getMinZ() - pos.getZ(), 0), pos.getZ() - bounds.getMaxZ());

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    public static BlockFace rotateFace(BlockFace originalFace, int rotation) {
        rotation = normalizeRotation(rotation);
        BlockFace[] clockwise = {
                BlockFace.NORTH,
                BlockFace.EAST,
                BlockFace.SOUTH,
                BlockFace.WEST,
                BlockFace.NORTH // 循环回起点
        };

        int startIndex = -1;
        for (int i = 0; i < 4; i++) {
            if (clockwise[i] == originalFace) {
                startIndex = i;
                break;
            }
        }

        if (startIndex == -1) return originalFace;

        int steps = rotation / 90;
        int newIndex = (startIndex + steps) % 4;
        return clockwise[newIndex];
    }

    public static BlockData rotateBlockData(BlockData data, int rotation) {
        if (data instanceof Directional) {
            Directional directional = (Directional) data;
            BlockFace face = directional.getFacing();

            // 处理上下方向的方块（如楼梯）
            if (face == BlockFace.UP || face == BlockFace.DOWN) {
                return data; // 保持原方向
            }

            BlockFace rotatedFace = rotateFace(face, rotation);
            directional.setFacing(rotatedFace);
            return directional;
        }
        return data;
    }
}