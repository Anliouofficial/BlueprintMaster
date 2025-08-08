package exe.example.blueprintMaster;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.hjson.JsonObject;
import org.hjson.JsonValue;
import org.hjson.ParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TemplateManager {
    private static final int MAX_VOLUME = 10000;
    private final TemplateManagerPlugin plugin;
    private final Map<UUID, String> pendingConfirmation = new HashMap<>();

    public TemplateManager(TemplateManagerPlugin plugin) {
        this.plugin = plugin;
    }

    public void saveTemplate(Player player, String name, String displayName, boolean explosionDamage) {
        UUID playerId = player.getUniqueId();
        String normalizedName = normalizeName(name);

        // 处理中文字符编码问题
        try {
            // 确保使用UTF-8编码处理文件名
            normalizedName = new String(normalizedName.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        if (!name.equals(normalizedName)) {
            player.sendMessage(ChatColor.GOLD + "名称已自动修正为: " + normalizedName);
        }

        File templateDir = new File(plugin.getDataFolder(), "templates/" + normalizedName);
        if (!templateDir.exists() && !templateDir.mkdirs()) {
            player.sendMessage(ChatColor.RED + "无法创建模板目录");
            return;
        }

        try {
            saveTemplateData(player, normalizedName, displayName, explosionDamage);
            pendingConfirmation.remove(playerId);
        } catch (TemplateSaveException e) {
            player.sendMessage(ChatColor.RED + e.getMessage());
            plugin.getLogger().severe("保存失败: " + e.getDetailedMessage());
        }
    }

    private void saveTemplateData(Player player, String name, String displayName, boolean explosionDamage) throws TemplateSaveException {
        UUID playerId = player.getUniqueId();
        Selection selection = plugin.getSelection(playerId);
        if (!selection.isValid()) {
            throw new TemplateSaveException("请先用选择工具划定区域",
                    "玩家未设置完整选区: " + player.getName());
        }

        int volume = selection.getVolume();
        if (volume > MAX_VOLUME) {
            throw new TemplateSaveException("错误：区域过大（最大" + MAX_VOLUME + "方块）",
                    "区域体积过大: " + volume + "方块");
        }

        File templateDir = new File(plugin.getDataFolder(), "templates/" + name);
        if (!templateDir.exists() && !templateDir.mkdirs()) {
            throw new TemplateSaveException("保存失败：无法创建目录",
                    "目录创建失败: " + templateDir.getAbsolutePath());
        }

        Path structurePath = new File(templateDir, "structure.bin").toPath();
        try {
            // 创建结构数据并进行深度验证
            byte[] structureData = createStructureData(selection);
            validateStructureData(structureData, true); // 深度验证

            // 使用临时文件安全写入
            Path tempPath = new File(templateDir, "structure.bin.tmp").toPath();
            Files.write(tempPath, structureData);

            // 验证临时文件完整性
            long tempSize = Files.size(tempPath);
            if (tempSize != structureData.length) {
                Files.deleteIfExists(tempPath);
                throw new TemplateSaveException("保存失败：文件写入不完整",
                        "文件大小不一致: " + tempSize + " != " + structureData.length);
            }

            // 原子操作重命名文件
            Files.move(tempPath, structurePath, StandardCopyOption.REPLACE_EXISTING);

            // 写入后验证文件内容
            byte[] writtenData = Files.readAllBytes(structurePath);
            if (!Arrays.equals(structureData, writtenData)) {
                throw new TemplateSaveException("文件写入不一致", "实际长度: " + writtenData.length);
            }

            // 计算校验和
            long checksum = plugin.calculateChecksum(writtenData);

            // 创建配置文件
            Path configPath = new File(templateDir, "data.conf").toPath();
            String configData = createConfigData(player, name, displayName, selection, checksum, explosionDamage);
            Files.writeString(configPath, configData, StandardCharsets.UTF_8);

            player.sendMessage(String.format(
                    ChatColor.GREEN + "成功保存模板 '%s'! 大小: %dx%dx%d",
                    name, selection.getWidth(), selection.getHeight(), selection.getLength()
            ));
        } catch (Exception e) {
            // 出错时删除损坏文件
            try {
                if (Files.exists(structurePath)) Files.delete(structurePath);
            } catch (IOException ex) {
                plugin.getLogger().warning("无法删除损坏文件: " + ex.getMessage());
            }

            throw new TemplateSaveException("保存失败：" + e.getMessage(), e.toString());
        }
    }

    private byte[] createStructureData(Selection selection) throws IOException {
        Location min = selection.getMinCorner();
        int width = selection.getWidth();
        int height = selection.getHeight();
        int length = selection.getLength();

        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("无效的选区尺寸: " + width + "x" + height + "x" + length);
        }

        // 使用内存缓存批量处理方块数据
        List<String> blockDataCache = new ArrayList<>(width * height * length);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    Block block = min.clone().add(x, y, z).getBlock();
                    BlockData data = block.getBlockData();
                    blockDataCache.add((data != null) ? data.getAsString(true) : "minecraft:air");
                }
            }
        }

        // 批量写入方块数据
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(gzipStream)) {

            // 写入文件头
            out.writeShort(1);
            out.writeShort(width);
            out.writeShort(height);
            out.writeShort(length);

            // 写入整个方块数据数组
            out.writeObject(blockDataCache.toArray(new String[0]));

            // 确保所有数据写入
            out.flush();
            gzipStream.finish();
            return byteStream.toByteArray();
        } catch (Exception e) {
            throw new IOException("方块数据序列化失败: " +  e.getMessage());
        }
    }

    // 增强版验证方法
    private void validateStructureData(byte[] data, boolean deepCheck) throws IOException {
        try (ByteArrayInputStream testStream = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(testStream);
             BukkitObjectInputStream in = new BukkitObjectInputStream(gzip)) {

            int version = in.readShort();
            int width = in.readShort();
            int height = in.readShort();
            int length = in.readShort();

            // 基本尺寸验证
            if (width <= 0 || height <= 0 || length <= 0) {
                throw new IOException("无效尺寸: " + width + "x" + height + "x" + length);
            }

            // 深度内容验证
            if (deepCheck) {
                String[] blocks = (String[]) in.readObject();
                int totalBlocks = width * height * length;

                if (blocks.length != totalBlocks) {
                    throw new IOException("方块数量不匹配: " + blocks.length + "/" + totalBlocks);
                }

                // 随机采样验证
                int sampleSize = Math.min(50, blocks.length);
                for (int i = 0; i < sampleSize; i++) {
                    int index = (int) (Math.random() * blocks.length);
                    try {
                        Bukkit.createBlockData(blocks[index]);
                    } catch (Exception e) {
                        throw new IOException("方块数据无效(" + index + "): " + blocks[index]);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("数据类型错误: " + e.getMessage());
        }
    }

    private String createConfigData(Player player, String name, String displayName,
                                    Selection selection, long checksum, boolean explosionDamage) {
        // 使用 StringBuilder 构建多行带注释的配置文件
        StringBuilder config = new StringBuilder();
        config.append("{\n");

        // 基本属性
        config.append("  \"name\": \"").append(name).append("\",\n");
        config.append("  \"display_name\": \"").append(displayName).append("\",\n");
        config.append("  \"creator\": \"").append(player.getName()).append("\",\n");
        config.append("  \"created\": \"").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneId.systemDefault()))).append("\",\n");

        // 建筑属性
        config.append("  \"build_speed\": ").append(5.0).append(",\n");
        config.append("  \"preview_color\": \"").append("#00FF00").append("\",\n");
        config.append("  \"opacity\": ").append(0.6).append(",\n");
        config.append("  \"rotation\": ").append(0).append(",\n");
        config.append("  \"custom_properties\": \"").append("无特殊属性").append("\",\n");
        config.append("  \"integrity\": ").append(100).append(",\n");
        config.append("  \"version\": ").append(1).append(",\n");
        config.append("  \"checksum\": ").append(checksum).append(",\n");

        // 新增吸引怪物属性
        config.append("  \"attract_monsters\": ").append(false).append(",\n");

        // 血量系统配置
        config.append("  \"base_health\": ").append(200.0).append(",\n");
        config.append("  \"armor\": ").append(15.0).append(",\n");
        config.append("  \"health_per_block\": ").append(0.2).append(",\n");
        // 爆炸伤害属性
        config.append("  \"explosion_damage\": ").append(explosionDamage).append(",\n");
        // 方块信息
        config.append("  \"block_info\": {\n");
        config.append("    \"total_blocks\": ").append(selection.getVolume()).append(",\n");
        config.append("    \"directional_blocks\": ").append(countDirectionalBlocks(selection)).append(",\n");
        config.append("    \"combustible_blocks\": ").append(countCombustibleBlocks(selection)).append("\n");
        config.append("  },\n");

        // 尺寸信息
        config.append("  \"dimensions\": {\n");
        config.append("    \"width\": ").append(selection.getWidth()).append(",\n");
        config.append("    \"height\": ").append(selection.getHeight()).append(",\n");
        config.append("    \"length\": ").append(selection.getLength()).append("\n");
        config.append("  },\n");

        // 建筑材料统计
        config.append("  \"material_stats\": {\n");
        config.append("    \"stone\": ").append(countBlocksByType(selection, Material.STONE)).append(",\n");
        config.append("    \"wood\": ").append(countBlocksByType(selection, Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS)).append(",\n");
        config.append("    \"glass\": ").append(countBlocksByType(selection, Material.GLASS)).append("\n");
        config.append("  }\n");

        config.append("}");

        return config.toString();
    }

    private int countCombustibleBlocks(Selection selection) {
        int count = 0;
        Location min = selection.getMinCorner();
        Set<Material> combustible = EnumSet.of(
                Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
                Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                Material.BOOKSHELF, Material.CHEST, Material.TNT
        );

        for (int y = 0; y < selection.getHeight(); y++) {
            for (int z = 0; z < selection.getLength(); z++) {
                for (int x = 0; x < selection.getWidth(); x++) {
                    Block block = min.clone().add(x, y, z).getBlock();
                    if (combustible.contains(block.getType())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int countBlocksByType(Selection selection, Material... materials) {
        int count = 0;
        Set<Material> materialSet = EnumSet.copyOf(Arrays.asList(materials));
        Location min = selection.getMinCorner();

        for (int y = 0; y < selection.getHeight(); y++) {
            for (int z = 0; z < selection.getLength(); z++) {
                for (int x = 0; x < selection.getWidth(); x++) {
                    Block block = min.clone().add(x, y, z).getBlock();
                    if (materialSet.contains(block.getType())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // 计算方向性方块数量
    private int countDirectionalBlocks(Selection selection) {
        int count = 0;
        Location min = selection.getMinCorner();
        for (int y = 0; y < selection.getHeight(); y++) {
            for (int z = 0; z < selection.getLength(); z++) {
                for (int x = 0; x < selection.getWidth(); x++) {
                    Block block = min.clone().add(x, y, z).getBlock();
                    BlockData data = block.getBlockData();
                    if (data instanceof Directional) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
    // 新增方法：获取所有模板名称
    public List<String> getTemplateNames() {
        List<String> names = new ArrayList<>();
        File templatesDir = new File(plugin.getDataFolder(), "templates");
        if (templatesDir.exists() && templatesDir.isDirectory()) {
            File[] dirs = templatesDir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    names.add(dir.getName());
                }
            }
        }
        return names;
    }
    private String normalizeName(String name) {
        // 移除文件名中的非法字符，但允许中文字符
        String normalized = name.replaceAll("[\\\\/:*?\"<>|]", "_"); // 仅替换文件系统非法字符
        normalized = normalized.replace(" ", "_"); // 空格替换为下划线

        // 限制长度，避免过长
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized;
    }

    private static class TemplateSaveException extends Exception {
        private final String detailedMessage;

        public TemplateSaveException(String userMessage, String detailedMessage) {
            super(userMessage);
            this.detailedMessage = detailedMessage;
        }

        public String getDetailedMessage() {
            return detailedMessage;
        }
    }

    public boolean templateExists(String name) {
        File templateDir = new File(plugin.getDataFolder(), "templates/" + name);
        return templateDir.exists() && templateDir.isDirectory();
    }

    public BuildingTemplate loadTemplate(String name) {
        File templateDir = new File(plugin.getDataFolder(), "templates/" + name);
        if (!templateDir.exists() || !templateDir.isDirectory()) {
            return null;
        }

        try {
            // 加载配置文件
            Path configPath = new File(templateDir, "data.conf").toPath();
            String configData = Files.readString(configPath, StandardCharsets.UTF_8);

            // 修复：添加空文件检查
            if (configData.trim().isEmpty()) {
                throw new IOException("配置文件为空");
            }

            JsonObject config = JsonValue.readJSON(configData).asObject();
            boolean explosionDamage = config.get("explosion_damage") != null ?
                    config.get("explosion_damage").asBoolean() : false;

            // 加载血量系统配置
            double baseHealth = config.get("base_health").asDouble();
            double armor = config.get("armor").asDouble();
            double healthPerBlock = config.get("health_per_block").asDouble();

            // 加载结构文件
            Path structurePath = new File(templateDir, "structure.bin").toPath();
            if (!Files.exists(structurePath)) {
                plugin.getLogger().severe("模板结构文件不存在: " + name);
                return null;
            }

            // 检查文件大小
            long fileSize = Files.size(structurePath);
            if (fileSize == 0) {
                plugin.getLogger().severe("模板结构文件大小为0: " + name);
                return null;
            }

            byte[] structureData = Files.readAllBytes(structurePath);

            // 验证校验和
            long expectedChecksum = config.get("checksum").asLong();
            long actualChecksum = plugin.calculateChecksum(structureData);

            if (expectedChecksum != actualChecksum) {
                plugin.getLogger().severe("模板校验失败: " + name);
                plugin.getLogger().severe("预期: " + expectedChecksum + " 实际: " + actualChecksum);

                // 删除损坏的模板
                try {
                    Files.deleteIfExists(configPath);
                    Files.deleteIfExists(structurePath);
                    Files.deleteIfExists(templateDir.toPath());
                    plugin.getLogger().info("已删除损坏的模板: " + name);
                } catch (IOException e) {
                    plugin.getLogger().warning("无法删除损坏的模板: " + name);
                }

                return null;
            }

            // 深度验证模板数据
            try {
                validateTemplateData(structureData, name);
            } catch (IOException e) {
                plugin.getLogger().severe("模板数据验证失败: " + e.getMessage());
                return null;
            }
            int totalBlocks = config.get("block_info").asObject().get("total_blocks").asInt();
            return new BuildingTemplate(
                    plugin,
                    name,
                    config.get("dimensions").asObject().get("width").asInt(),
                    config.get("dimensions").asObject().get("height").asInt(),
                    config.get("dimensions").asObject().get("length").asInt(),
                    config.get("build_speed").asDouble(),
                    structureData,
                    baseHealth,
                    armor,
                    healthPerBlock,
                    totalBlocks,
                    explosionDamage
            );
        } catch (IOException | ParseException e) {
            plugin.getLogger().severe("加载模板失败: " + e.getMessage());

            // 增强错误日志输出
            plugin.getLogger().severe("模板名称: " + name);
            if (e.getCause() != null) {
                plugin.getLogger().severe("错误原因: " + e.getCause().getMessage());
            }

            // 尝试读取配置文件内容
            try {
                Path configPath = new File(templateDir, "data.conf").toPath();
                if (Files.exists(configPath)) {
                    String configData = Files.readString(configPath, StandardCharsets.UTF_8);
                    plugin.getLogger().severe("配置文件内容: " + configData);
                }
            } catch (Exception ex) {
                plugin.getLogger().severe("无法读取配置文件: " + ex.getMessage());
            }

            return null;
        }
    }

    // 模板数据验证
    private void validateTemplateData(byte[] data, String name) throws IOException {
        try (ByteArrayInputStream testStream = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(testStream);
             BukkitObjectInputStream in = new BukkitObjectInputStream(gzip)) {

            // 读取文件头
            int version = in.readShort();
            int width = in.readShort();
            int height = in.readShort();
            int length = in.readShort();

            // 验证尺寸
            if (width <= 0 || height <= 0 || length <= 0) {
                throw new IOException("无效的模板尺寸: " + width + "x" + height + "x" + length);
            }

            // 加载方块数据
            String[] blocks = (String[]) in.readObject();
            int totalBlocks = width * height * length;

            // 验证方块数量
            if (blocks.length != totalBlocks) {
                throw new IOException("方块数量不匹配: " + blocks.length + "/" + totalBlocks);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("数据类型错误: " + e.getMessage());
        }
    }
}