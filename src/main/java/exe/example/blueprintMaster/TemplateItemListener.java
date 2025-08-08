package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.Map.Entry;

public class TemplateItemListener implements Listener {
    private final TemplateManagerPlugin plugin;
    private final Map<UUID, List<OutlineData>> playerOutlines = new HashMap<>();
    private Location lastOutlineLocation;
    private static final Color VALID_COLOR = Color.fromRGB(0, 255, 0);
    private static final Color INVALID_COLOR = Color.fromRGB(255, 0, 0);
    private static final Color PLACED_COLOR = Color.fromRGB(255, 255, 0);
    private static final Color FRONT_COLOR = Color.fromRGB(255, 255, 255);
    private static final Color PLACED_BLUEPRINT_COLOR = Color.fromRGB(0, 150, 255);

    private static final double PARTICLE_SPACING = 0.5;
    private static final int MAX_DISTANCE = 50;
    private static final double ATTACH_RANGE = 10.0;
    public Location getLastOutlineLocation() {
        return lastOutlineLocation;
    }
    public TemplateItemListener(TemplateManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!TemplateItem.isTemplateItem(item)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        String templateName = TemplateItem.getTemplateName(item);
        int rotation = TemplateItem.getRotation(item);
        boolean attachMode = TemplateItem.getAttachMode(item);

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                placeBuilding(player, item, templateName);
                event.setCancelled(true);
                return;
            }

            placeOutline(player, templateName, rotation, attachMode, event.getBlockFace());
            event.setCancelled(true);
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR) {
            if (player.isSneaking()) {
                // 限制为90°旋转
                rotation = (rotation + 90) % 360;
                player.getInventory().setItemInMainHand(
                        TemplateItem.updateItem(item, rotation, attachMode)
                );
                player.sendActionBar(ChatColor.YELLOW + "旋转: " + rotation + "°");

                // 更新现有轮廓的旋转
                updateOutlineRotation(player, rotation);
                event.setCancelled(true);
                return;
            }

            attachMode = !attachMode;
            player.getInventory().setItemInMainHand(
                    TemplateItem.updateItem(item, rotation, attachMode)
            );
            player.sendActionBar(ChatColor.YELLOW + "吸附模式: " +
                    (attachMode ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
            event.setCancelled(true);
        }
    }

    // 更新现有轮廓的旋转
    private void updateOutlineRotation(Player player, int newRotation) {
        List<OutlineData> outlines = playerOutlines.getOrDefault(player.getUniqueId(), Collections.emptyList());
        for (OutlineData outline : outlines) {
            if (!outline.isPlaced()) {
                // 保持相同位置，只更新旋转
                outline.setRotation(newRotation);
                outline.setRotatedWidth(RotationUtil.getRotatedWidth(newRotation,
                        outline.getWidth(), outline.getLength()));
                outline.setRotatedLength(RotationUtil.getRotatedLength(newRotation,
                        outline.getWidth(), outline.getLength()));
            }
        }
    }

    private Location calculateBottomCenter(Player player, BlockFace clickedFace, String templateName, int rotation) {
        Block targetBlock = player.getTargetBlockExact(10);
        if (targetBlock == null) return null;

        // Calculate surface center with rotation
        Location surfaceLoc = targetBlock.getRelative(clickedFace).getLocation();
        surfaceLoc.add(0.5, 0, 0.5); // Center of the block

        // Align to rotation grid
        int gridSize = 1;
        double x = Math.floor(surfaceLoc.getX() / gridSize) * gridSize + 0.5;
        double z = Math.floor(surfaceLoc.getZ() / gridSize) * gridSize + 0.5;

        return new Location(surfaceLoc.getWorld(), x, surfaceLoc.getY(), z);
    }

    private void placeOutline(Player player, String templateName, int rotation, boolean attachMode, BlockFace clickedFace) {
        Block targetBlock = player.getTargetBlockExact(10);
        if (targetBlock == null) return;
        Location placementLoc = targetBlock.getRelative(clickedFace).getLocation();
        Location bottomCenter = RotationUtil.alignToGrid(placementLoc.add(0.5, 0, 0.5)); // 关键

        // 更新最后轮廓位置
        this.lastOutlineLocation = bottomCenter;
        placementLoc.add(0.5, 0, 0.5); // 方块中心

        // 对齐到网格
        Location baseLocation = null;
        OutlineData attachedTo = null;
        if (attachMode) {
            List<OutlineData> nearbyPlaced = new ArrayList<>();
            for (OutlineData outline : plugin.getNearbyPlacedOutlines(player.getLocation(), ATTACH_RANGE)) {
                nearbyPlaced.add(outline);
            }

            List<OutlineData> playerOutlines = this.playerOutlines.getOrDefault(player.getUniqueId(), Collections.emptyList());
            for (OutlineData outline : playerOutlines) {
                if (player.getLocation().distance(outline.getLocation()) <= ATTACH_RANGE) {
                    nearbyPlaced.add(outline);
                }
            }
            if (bottomCenter != null) {
                bottomCenter = RotationUtil.alignToGrid(bottomCenter);
            }
            if (!nearbyPlaced.isEmpty()) {
                Vector lookDir = player.getLocation().getDirection().normalize();
                attachedTo = findNearestOutline(player, nearbyPlaced, lookDir);
                if (attachedTo != null) {
                    baseLocation = calculateAttachLocation(player, attachedTo, lookDir, rotation);
                    if (baseLocation != null) {
                        player.sendActionBar(ChatColor.GREEN + "已吸附到轮廓");
                    }
                }
            }
        }

        if (baseLocation == null) {
            // 修复：使用存在的计算方法
            baseLocation = RotationUtil.calculateBlockPosition(
                    bottomCenter,
                    0, 0, 0, // 中心点坐标
                    getTemplateWidth(templateName),
                    getTemplateLength(templateName),
                    rotation
            );
        }

        // 添加调试日志
        player.sendMessage(ChatColor.GRAY + "轮廓位置: X=" + bottomCenter.getX() +
                " Y=" + bottomCenter.getY() + " Z=" + bottomCenter.getZ());

        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(templateName);
        if (template == null) {
            player.sendActionBar(ChatColor.RED + "加载模板失败: " + templateName);
            return;
        }

        int rotatedWidth = RotationUtil.getRotatedWidth(rotation, template.getWidth(), template.getLength());
        int rotatedLength = RotationUtil.getRotatedLength(rotation, template.getWidth(), template.getLength());

        Location cornerForValidation = bottomCenter.clone().subtract(
                rotatedWidth / 2.0,
                0,
                rotatedLength / 2.0
        );

        boolean isValid = validateLocation(player, cornerForValidation, rotatedWidth, template.getHeight(), rotatedLength);

        OutlineData outline = new OutlineData(
                templateName,
                bottomCenter, // 底部中心点
                rotation,
                System.currentTimeMillis(),
                isValid,
                template.getWidth(),
                template.getHeight(),
                template.getLength(),
                rotatedWidth,
                rotatedLength,
                attachedTo != null ? attachedTo.getLocation() : null
        );

        List<OutlineData> outlines = playerOutlines.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayList<>()
        );
        outlines.removeIf(o -> !o.isPlaced()); // 移除所有未放置的旧轮廓
        outlines.add(outline); // 添加新轮廓

        if (isValid) {
            player.sendActionBar(ChatColor.GREEN + "轮廓已放置");
        } else {
            player.sendActionBar(ChatColor.RED + "位置无效 - 红色轮廓");
        }
    }
    public void removeOutlineByLocation(Location center) {
        for (List<OutlineData> outlines : playerOutlines.values()) {
            outlines.removeIf(outline ->
                    outline.getLocation().equals(center)
            );
        }
    }
    // 辅助方法：获取模板的原始宽度
    private int getTemplateWidth(String templateName) {
        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(templateName);
        return template != null ? template.getWidth() : 0;
    }

    // 辅助方法：获取模板的原始长度
    private int getTemplateLength(String templateName) {
        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(templateName);
        return template != null ? template.getLength() : 0;
    }

    private void placeBuilding(Player player, ItemStack item, String templateName) {
        OutlineData outline = findNearestOutline(player);
        if (outline == null) {
            player.sendActionBar(ChatColor.RED + "附近没有轮廓");
            return;
        }

        if (!outline.isValid()) {
            player.sendActionBar(ChatColor.RED + "轮廓位置无效");
            return;
        }

        generateBuilding(player, outline, item);
    }

    private void generateBuilding(Player player, OutlineData outline, ItemStack item) {
        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(outline.getTemplateName());
        if (template == null) {
            player.sendActionBar(ChatColor.RED + "加载模板失败");
            plugin.getLogger().warning("Failed to load template: " + outline.getTemplateName());
            return;
        }

        template.generate(player, outline.getLocation(), outline.getRotation());

        if (!player.getGameMode().equals(GameMode.CREATIVE)) {
            item.setAmount(item.getAmount() - 1);
        }

        outline.setPlaced(true);
        plugin.addPermanentOutline(outline.getLocation(),
                template.getWidth(), template.getHeight(), template.getLength(), outline.getRotation());
    }

    public void renderOutlines() {

        for (Entry<UUID, List<OutlineData>> entry : playerOutlines.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;

            // 检查是否手持模板物品
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            boolean holdingTemplate = TemplateItem.isTemplateItem(mainHand) || TemplateItem.isTemplateItem(offHand);

            if (holdingTemplate) {
                // 渲染50格内的所有轮廓
                for (OutlineData outline : entry.getValue()) {
                    if (player.getLocation().distance(outline.getLocation()) <= 50) {
                        renderOutline(player, outline, true);
                    }
                }
                // 渲染已放置的永久轮廓（黄色）
                for (OutlineData outline : plugin.getNearbyPlacedOutlines(player.getLocation(), 50)) {
                    renderOutline(player, outline, false);
                }
            } else {
                // 不显示轮廓时清除预览数据
                entry.getValue().clear();
            }
            // === 修复点2: 移除无效的distance计算 ===
            // 原代码:
            // double distance = player.getLocation().distance(outline.getLocation());
            // if (distance > 30) continue; // 只渲染30格内的轮廓
        }
    }

    private void renderOutline(Player player, OutlineData outline, boolean isPreview) {
        Color color;
        if (outline.isPlaced()) {
            color = PLACED_BLUEPRINT_COLOR;
        } else {
            color = outline.isValid() ? VALID_COLOR : INVALID_COLOR;
        }

        renderWireframe(player, outline, isPreview, color);
        drawFrontMarker(player, outline);
    }

    private void renderWireframe(Player player, OutlineData outline, boolean isPreview, Color color) {
        // 关键修复：使用原始尺寸+旋转角度计算角点
        Vector[] corners = RotationUtil.calculateCorners(
                outline.getLocation(),
                outline.getWidth(),   // 使用原始宽度
                outline.getLength(),   // 使用原始长度
                outline.getRotation()  // 旋转角度
        );

        // Draw bottom quad
        for (int i = 0; i < 4; i++) {
            Vector startVec = corners[i];
            Vector endVec = corners[(i + 1) % 4];
            Location start = new Location(outline.getLocation().getWorld(), startVec.getX(), outline.getLocation().getY(), startVec.getZ());
            Location end = new Location(outline.getLocation().getWorld(), endVec.getX(), outline.getLocation().getY(), endVec.getZ());
            drawLine(player, start, end, PARTICLE_SPACING, color);
        }

        // Draw top quad
        for (int i = 0; i < 4; i++) {
            Vector startVec = corners[i].clone().add(new Vector(0, outline.getHeight(), 0));
            Vector endVec = corners[(i + 1) % 4].clone().add(new Vector(0, outline.getHeight(), 0));
            Location start = new Location(outline.getLocation().getWorld(), startVec.getX(), startVec.getY(), startVec.getZ());
            Location end = new Location(outline.getLocation().getWorld(), endVec.getX(), endVec.getY(), endVec.getZ());
            drawLine(player, start, end, PARTICLE_SPACING, color);
        }

        // Draw vertical lines
        for (Vector corner : corners) {
            Location bottom = new Location(outline.getLocation().getWorld(), corner.getX(), outline.getLocation().getY(), corner.getZ());
            Location top = new Location(outline.getLocation().getWorld(), corner.getX(), corner.getY() + outline.getHeight(), corner.getZ());
            drawLine(player, bottom, top, PARTICLE_SPACING, color);
        }
    }

    private void drawLine(Player player, Location start, Location end, double spacing, Color color) {
        Vector direction = end.toVector().subtract(start.toVector());
        double length = direction.length();
        direction.normalize();

        // 增加粒子密度（间距减少为原来的1/2）
        double adjustedSpacing = spacing * 0.5;

        for (double d = 0; d < length; d += adjustedSpacing) {
            Location point = start.clone().add(direction.clone().multiply(d));
            player.spawnParticle(Particle.DUST, point, 1,
                    new Particle.DustOptions(color, 1.5f)); // 增加粒子大小
        }
    }

    private void drawFrontMarker(Player player, OutlineData outline) {
        // 获取正面向量
        Vector front = RotationUtil.getFrontVector(outline.getRotation());
        // 计算正面中心位置（在建筑高度中心，并向前突出）
        Location frontCenter = outline.getLocation().clone()
                .add(0, outline.getHeight() / 2.0, 0)
                .add(front.multiply(outline.getRotatedLength() / 2.0));

        // 计算正面矩形的四个角（比建筑轮廓小一圈，缩小0.2格）
        double halfWidth = outline.getRotatedWidth() / 2.0 - 0.2;
        double halfHeight = outline.getHeight() / 2.0 - 0.2;
        // 注意：我们只画正面，所以深度方向为0
        Vector up = new Vector(0, 1, 0);
        Vector right = front.clone().rotateAroundY(Math.PI / 2).normalize().multiply(halfWidth);

        // 四个角点
        Location topRight = frontCenter.clone().add(right).add(0, halfHeight, 0);
        Location topLeft = frontCenter.clone().subtract(right).add(0, halfHeight, 0);
        Location bottomRight = frontCenter.clone().add(right).subtract(0, halfHeight, 0);
        Location bottomLeft = frontCenter.clone().subtract(right).subtract(0, halfHeight, 0);

        // 绘制四条边
        drawLine(player, topRight, topLeft, PARTICLE_SPACING, FRONT_COLOR);
        drawLine(player, topLeft, bottomLeft, PARTICLE_SPACING, FRONT_COLOR);
        drawLine(player, bottomLeft, bottomRight, PARTICLE_SPACING, FRONT_COLOR);
        drawLine(player, bottomRight, topRight, PARTICLE_SPACING, FRONT_COLOR);
    }


    private OutlineData findNearestOutline(Player player) {
        return findNearestOutline(player, playerOutlines.getOrDefault(player.getUniqueId(), Collections.emptyList()), null);
    }

    private OutlineData findNearestOutline(Player player, List<OutlineData> outlines, Vector direction) {
        if (outlines == null || outlines.isEmpty()) return null;

        Location playerLoc = player.getLocation();
        OutlineData nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (OutlineData outline : outlines) {
            if (outline.isPlaced()) continue;

            double distance = playerLoc.distance(outline.getLocation());
            double directionSimilarity = 1.0;

            if (direction != null) {
                Vector toOutline = outline.getLocation().toVector().subtract(playerLoc.toVector()).normalize();
                directionSimilarity = direction.dot(toOutline);
            }

            double score = distance * (2.0 - directionSimilarity);

            if (score < minDistance) {
                minDistance = score;
                nearest = outline;
            }
        }

        return nearest;
    }

    private Location calculateAttachLocation(Player player, OutlineData baseOutline, Vector attachDirection, int newRotation) {
        String templateName = TemplateItem.getTemplateName(player.getInventory().getItemInMainHand());
        BuildingTemplate newTemplate = plugin.getTemplateManager().loadTemplate(templateName);
        if (newTemplate == null) return null;

        int baseRotation = baseOutline.getRotation();
        double baseWidth = baseOutline.getRotatedWidth();
        double baseLength = baseOutline.getRotatedLength();
        double newWidth = RotationUtil.getRotatedWidth(newRotation, newTemplate.getWidth(), newTemplate.getLength());
        double newLength = RotationUtil.getRotatedLength(newRotation, newTemplate.getWidth(), newTemplate.getLength());

        Vector baseFront = RotationUtil.getFrontVector(baseRotation);
        Vector baseRight = new Vector(-baseFront.getZ(), 0, baseFront.getX());

        BlockFace attachFace = calculateAttachFace(baseFront, baseRight, attachDirection);

        Location baseCenter = baseOutline.getLocation().clone();
        Vector offset = new Vector();

        switch (attachFace) {
            case NORTH:
                offset = new Vector(0, 0, -(baseLength + newLength) / 2.0);
                break;
            case SOUTH:
                offset = new Vector(0, 0, (baseLength + newLength) / 2.0);
                break;
            case EAST:
                offset = new Vector((baseWidth + newWidth) / 2.0, 0, 0);
                break;
            case WEST:
                offset = new Vector(-(baseWidth + newWidth) / 2.0, 0, 0);
                break;
        }

        if (baseRotation != 0) {
            double angle = Math.toRadians(-baseRotation);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            offset = new Vector(newX, 0, newZ);
        }

        return baseCenter.add(offset);
    }

    private BlockFace calculateAttachFace(Vector front, Vector right, Vector direction) {
        Vector north = front.clone().multiply(-1);
        Vector south = front.clone();
        Vector east = right.clone();
        Vector west = right.clone().multiply(-1);

        double northDot = direction.dot(north);
        double southDot = direction.dot(south);
        double eastDot = direction.dot(east);
        double westDot = direction.dot(west);

        double maxDot = Math.max(Math.max(northDot, southDot), Math.max(eastDot, westDot));

        if (maxDot == northDot) return BlockFace.NORTH;
        if (maxDot == southDot) return BlockFace.SOUTH;
        if (maxDot == eastDot) return BlockFace.EAST;
        return BlockFace.WEST;
    }

    private boolean validateLocation(Player player, Location location, int width, int height, int length) {
        if (location.getY() < location.getWorld().getMinHeight() ||
                location.getY() + height > location.getWorld().getMaxHeight()) {
            return false;
        }

        for (int y = 0; y < height; y += 2) {
            for (int z = 0; z < length; z += 2) {
                for (int x = 0; x < width; x += 2) {
                    Block block = location.clone().add(x, y, z).getBlock();
                    if (!block.isPassable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static class OutlineData {
        private String templateName;
        private Location location;
        private int rotation;
        private long timestamp;
        private boolean valid;
        private boolean placed;
        private int width;
        private int height;
        private int length;
        private int rotatedWidth;
        private int rotatedLength;
        private Location attachedTo;

        public OutlineData(String templateName, Location location, int rotation, long timestamp,
                           boolean valid, int width, int height, int length, int rotatedWidth, int rotatedLength, Location attachedTo) {
            this.templateName = templateName;
            this.location = location;
            this.rotation = rotation;
            this.timestamp = timestamp;
            this.valid = valid;
            this.placed = false;
            this.width = width;
            this.height = height;
            this.length = length;
            this.rotatedWidth = rotatedWidth;
            this.rotatedLength = rotatedLength;
            this.attachedTo = attachedTo;
        }

        public String getTemplateName() { return templateName; }
        public Location getLocation() { return location; }
        public int getRotation() { return rotation; }
        public boolean isValid() { return valid; }
        public boolean isPlaced() { return placed; }
        public void setPlaced(boolean placed) { this.placed = placed; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getLength() { return length; }
        public int getRotatedWidth() { return rotatedWidth; }
        public int getRotatedLength() { return rotatedLength; }
        public long getTimestamp() { return timestamp; }
        public Location getAttachedTo() { return attachedTo; }

        public void setRotation(int rotation) {
            this.rotation = rotation;
        }

        public void setRotatedWidth(int rotatedWidth) {
            this.rotatedWidth = rotatedWidth;
        }

        public void setRotatedLength(int rotatedLength) {
            this.rotatedLength = rotatedLength;
        }
    }
    public void renderOutlines(Player player) {
        // 检查玩家是否手持模板物品
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean holdingTemplate = TemplateItem.isTemplateItem(mainHand) || TemplateItem.isTemplateItem(offHand);

        if (!holdingTemplate) {
            List<OutlineData> outlines = playerOutlines.get(player.getUniqueId());
            if (outlines != null) {
                outlines.clear();
            }
            return;
        }

        // 只渲染玩家50格内的轮廓
        List<OutlineData> outlines = playerOutlines.getOrDefault(player.getUniqueId(), new ArrayList<>());
        for (OutlineData outline : outlines) {
            if (player.getLocation().distance(outline.getLocation()) <= 50) {
                renderOutline(player, outline, true);
            }
        }
        for (OutlineData outline : outlines) {
            // 新增世界检查
            if (!outline.getLocation().getWorld().equals(player.getWorld())) continue;

            if (player.getLocation().distance(outline.getLocation()) <= 50) {
                renderOutline(player, outline, true);
            }
        }
        // 渲染已放置的永久轮廓（黄色）
        for (OutlineData outline : plugin.getNearbyPlacedOutlines(player.getLocation(), 50)) {
            renderOutline(player, outline, false);
        }
    }
}