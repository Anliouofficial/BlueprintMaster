package exe.example.blueprintMaster;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class BuildingTemplate {

    private final String displayName;
    private final boolean explosionDamage;
    private final String name;
    private final int width;
    private final int height;
    private final int length;
    private final double buildSpeed;
    private final byte[] structureData;
    private final TemplateManagerPlugin plugin;
    private final double baseHealth;
    private final double armor;
    private final double healthPerBlock;
    private final int totalBlocks;

    public BuildingTemplate(TemplateManagerPlugin plugin, String name, int width, int height, int length,
                            double buildSpeed, byte[] structureData,
                            double baseHealth, double armor, double healthPerBlock, int totalBlocks,
                            boolean explosionDamage) {
        this.plugin = plugin;
        this.name = name;
        this.width = width;
        this.height = height;
        this.length = length;
        this.buildSpeed = buildSpeed;
        this.structureData = structureData;
        this.baseHealth = baseHealth;
        this.armor = armor;
        this.healthPerBlock = healthPerBlock;
        this.totalBlocks = totalBlocks;
        this.explosionDamage = explosionDamage;
        this.displayName = name; // 临时修复，使用模板名作为显示名
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getLength() { return length; }
    public double getBuildSpeed() { return buildSpeed; }
    public byte[] getStructureData() { return structureData; }
    public double getBaseHealth() { return baseHealth; }
    public double getArmor() { return armor; }
    public double getHealthPerBlock() { return healthPerBlock; }
    public int getTotalBlocks() { return totalBlocks; }

    public void generate(Player player, Location placementLoc, int rotation) {
        // 计算底部中心点
        Location bottomCenter = placementLoc.clone();

        // 计算总血量
        double totalHealth = baseHealth + healthPerBlock * totalBlocks;

        // 生成建筑ID
        UUID buildingId = UUID.randomUUID();

        // 确定所有者ID（处理player为null的情况）
        UUID ownerId = player != null ? player.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");

        // 注册建筑（主线程）
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getHealthSystem().registerBuilding(
                    buildingId,
                    ownerId,  // 使用实际或默认的ownerId
                    bottomCenter,
                    width,
                    height,
                    length,
                    rotation,
                    true,
                    totalHealth,
                    armor,
                    this.explosionDamage,
                    false
            );
        });

        // 异步加载方块数据
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> blockDataList = loadBlockDataSync();
                if (blockDataList == null || blockDataList.isEmpty()) {
                    if (player != null) player.sendMessage(ChatColor.RED + "模板数据加载失败");
                    return;
                }

                BuildingGeneratorTask task = new BuildingGeneratorTask(
                        buildingId,
                        player,  // 可为null
                        bottomCenter,
                        blockDataList,
                        width, height, length,
                        blockDataList.size(),
                        name,
                        rotation,
                        this.buildSpeed
                );

                double secondsPerBlock = buildSpeed / blockDataList.size();
                long ticksPerBlock = (long) (secondsPerBlock * 20);
                task.runTaskTimer(plugin, 0, Math.max(1, ticksPerBlock));
            } catch (Exception e) {
                if (player != null) player.sendMessage(ChatColor.RED + "生成建筑时发生错误");
                plugin.getLogger().severe("生成建筑时出错: " + e.getMessage());
            }
        });
    }

    private List<String> loadBlockDataSync() {
        if (structureData == null) {
            plugin.getLogger().warning("模板数据为空: " + name);
            return Collections.emptyList();
        }

        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(structureData);
             GZIPInputStream gzipStream = new GZIPInputStream(byteStream);
             BukkitObjectInputStream in = new BukkitObjectInputStream(gzipStream)) {

            in.readShort(); // version
            in.readShort(); // width
            in.readShort(); // height
            in.readShort(); // length

            return Arrays.asList((String[]) in.readObject());

        } catch (Exception e) {
            plugin.getLogger().severe("模板数据同步加载失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private class BuildingGeneratorTask extends BukkitRunnable {
        private final UUID buildingId;
        private final Player player;
        private final Location bottomCenter;
        private final List<String> blockDataList;
        private final int width;
        private final int height;
        private final int length;
        private final int totalBlocks;
        private final String templateName;
        private final int rotation;
        private final double buildSpeed;

        private final List<List<BlockInfo>> groupedBlocks = new ArrayList<>();
        private int currentGroupIndex = 0;

        public BuildingGeneratorTask(UUID buildingId, Player player, Location bottomCenter,
                                     List<String> blockDataList, int width, int height, int length,
                                     int totalBlocks, String templateName, int rotation, double buildSpeed) {
            this.bottomCenter = bottomCenter;
            this.buildingId = buildingId;
            this.player = player;
            this.blockDataList = blockDataList;
            this.width = width;
            this.height = height;
            this.length = length;
            this.totalBlocks = totalBlocks;
            this.templateName = templateName;
            this.rotation = rotation;
            this.buildSpeed = buildSpeed;

            prepareRadialGroups();
        }

        private void prepareRadialGroups() {
            Map<Integer, List<BlockInfo>> distanceMap = new TreeMap<>();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        Location loc = RotationUtil.calculateBlockPosition(
                                bottomCenter,
                                x, y, z,
                                width, length,
                                rotation
                        );
                        double distance = loc.distance(bottomCenter);
                        int distanceInt = (int) Math.round(distance);
                        distanceMap.computeIfAbsent(distanceInt, k -> new ArrayList<>())
                                .add(new BlockInfo(x, y, z, loc));
                    }
                }
            }
            groupedBlocks.addAll(distanceMap.values());
        }

        @Override
        public void run() {
            try {
                // 检查玩家是否离线（新增null检查）
                if (player != null && !player.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getHealthSystem().completelyRemoveBuilding(buildingId);
                    });
                    this.cancel();
                    return;
                }

                // 检查建筑是否已被移除
                if (!plugin.getHealthSystem().getBuildings().containsKey(buildingId)) {
                    this.cancel();
                    return;
                }
                // 每次处理多个组（最多5组）
                int groupsToProcess = Math.min(5, groupedBlocks.size() - currentGroupIndex);

                for (int g = 0; g < groupsToProcess; g++) {
                    List<BlockInfo> group = groupedBlocks.get(currentGroupIndex);
                    for (BlockInfo blockInfo : group) {
                        int idx = blockInfo.y * width * length + blockInfo.z * width + blockInfo.x;
                        if (idx < 0 || idx >= blockDataList.size()) continue;

                        String blockDataStr = blockDataList.get(idx);
                        if (blockDataStr == null) continue;

                        BlockData blockData = Bukkit.createBlockData(blockDataStr);
                        BlockData rotatedData = RotationUtil.rotateBlockData(blockData, rotation);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Block block = blockInfo.location.getBlock();
                            block.setBlockData(rotatedData);
                        });
                    }
                    currentGroupIndex++;
                }

                // 检查是否完成
                if (currentGroupIndex >= groupedBlocks.size()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // 仅在玩家不为null时发送消息
                        if (player != null) {
                            player.sendMessage(ChatColor.GREEN + "建筑生成完成: " + templateName);
                            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
                        }
                        plugin.getHealthSystem().buildingCompleted(buildingId);
                    });
                    this.cancel();
                }
            } catch (Exception e) {
                // 仅在玩家不为null时发送错误消息
                if (player != null) {
                    player.sendActionBar(ChatColor.RED + "生成错误: " + e.getMessage());
                }
                plugin.getLogger().severe("生成建筑时出错: " + e.getMessage());
                this.cancel();
            }
        }

        private void drawRadialParticles(double radius, Particle particle) {
            int points = 30;
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double dx = radius * Math.cos(angle);
                double dz = radius * Math.sin(angle);
                Location point = bottomCenter.clone().add(dx, 0, dz);
                player.spawnParticle(particle, point, 1);
            }
        }

        private static class BlockInfo {
            final int x, y, z;
            final Location location;

            BlockInfo(int x, int y, int z, Location location) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.location = location;
            }
        }
    }
}