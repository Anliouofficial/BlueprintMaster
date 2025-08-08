package exe.example.blueprintMaster;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

public class TemplateManagerPlugin extends JavaPlugin {

    private BuildingRepairSystem repairSystem;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private TemplateManager templateManager;
    private TemplateItemListener itemListener;
    private final Map<Location, TemplateItemListener.OutlineData> placedOutlines = new ConcurrentHashMap<>();
    private BuildingHealthSystem healthSystem;
    private BuildingProtectionSystem protectionSystem;
    private BuildingCollapseSystem collapseSystem;

    @Override
    public void onEnable() {
        TemplateCommand templateCommand = new TemplateCommand(this);
        getCommand("template").setExecutor(templateCommand);
        getCommand("template").setTabCompleter(templateCommand);
        saveDefaultConfig();

        healthSystem = new BuildingHealthSystem(this);
        repairSystem = new BuildingRepairSystem(this);
        getServer().getPluginManager().registerEvents(repairSystem, this);

        BuildingMonsterAttackSystem attackSystem = healthSystem.getMonsterAttackSystem();
        collapseSystem = healthSystem.getCollapseSystem();

        protectionSystem = new BuildingProtectionSystem(healthSystem);
        getServer().getPluginManager().registerEvents(protectionSystem, this);

        getServer().getPluginManager().registerEvents(new EvokerFangsListener(attackSystem), this);
        getServer().getPluginManager().registerEvents(
                new ProjectileDamageListener(this, healthSystem, attackSystem), this);
        getServer().getPluginManager().registerEvents(healthSystem, this);

        templateManager = new TemplateManager(this);
        itemListener = new TemplateItemListener(this);

        getCommand("template").setExecutor(new TemplateCommand(this));
        getServer().getPluginManager().registerEvents(new SelectionWandListener(this), this);
        getServer().getPluginManager().registerEvents(itemListener, this);

        startParticleRenderer();
        cleanCorruptedTemplates();
        startOutlineRendering();

        getLogger().info("TemplateManager 已启用!");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        getLogger().info("重新加载配置文件...");
        reloadSystems();
        templateManager = new TemplateManager(this);
        getLogger().info("配置重载完成!");
    }

    private void reloadSystems() {
        HandlerList.unregisterAll(this);
        healthSystem = new BuildingHealthSystem(this);
        repairSystem = new BuildingRepairSystem(this);
        protectionSystem = new BuildingProtectionSystem(healthSystem);

        getServer().getPluginManager().registerEvents(healthSystem, this);
        getServer().getPluginManager().registerEvents(repairSystem, this);
        getServer().getPluginManager().registerEvents(protectionSystem, this);

        BuildingMonsterAttackSystem attackSystem = healthSystem.getMonsterAttackSystem();
        getServer().getPluginManager().registerEvents(new EvokerFangsListener(attackSystem), this);
        getServer().getPluginManager().registerEvents(
                new ProjectileDamageListener(this, healthSystem, attackSystem), this);
        getServer().getPluginManager().registerEvents(itemListener, this);

        startParticleRenderer();
        startOutlineRendering();
    }

    @Override
    public void saveDefaultConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                Files.writeString(
                        configFile.toPath(),
                        getDefaultConfigContent(),
                        StandardCharsets.UTF_8
                );
            } catch (IOException e) {
                getLogger().severe("无法创建默认配置文件: " + e.getMessage());
            }
        }
    }

    private String getDefaultConfigContent() {
        return "# TemplateManager 配置\n" +
                "# 默认建筑生成速度（秒/方块）\n" +
                "default_build_speed: 5.0\n\n" +
                "# 建筑轮廓预览颜色（HEX格式）\n" +
                "preview_color: \"#00FF00\"\n\n" +
                "# 轮廓透明度（0.0-1.0）\n" +
                "opacity: 0.6\n\n" +
                "# 最大保存区域体积（方块数）\n" +
                "max_volume: 10000\n\n" +
                "# 血量系统配置\n" +
                "health_system:\n" +
                "  # 基础血量值\n" +
                "  base_health: 200.0\n" +
                "  # 基础护甲值\n" +
                "  armor: 15.0\n" +
                "  # 每方块增加的血量\n" +
                "  health_per_block: 0.2\n" +
                "  # 是否开启爆炸伤害\n" +
                "  explosion_damage: false\n" +
                "  # 是否吸引怪物\n" +
                "  attract_monsters: false\n\n" +
                "# 攻击系统配置\n" +
                "attack_system:\n" +
                "  # 近战攻击范围\n" +
                "  melee_range: 1.5\n" +
                "  # 远程攻击范围\n" +
                "  projectile_range: 2.0\n" +
                "  # 苦力怕爆炸范围\n" +
                "  creeper_range: 3.0\n" +
                "  # 建筑边界扩展值\n" +
                "  building_margin: 0.5\n" +
                "  # 怪物攻击范围配置\n" +
                "  mob_ranges:\n" +
                "    skeleton: 10.0\n" +
                "    witch: 6.0\n" +
                "    blaze: 5.0\n" +
                "    evoker: 3.0\n" +
                "    pillager: 8.0\n" +
                "    ghast: 30.0\n\n" +
                "# 维修系统配置\n" +
                "repair_system:\n" +
                "  # 基础维修速度（HP/秒）\n" +
                "  repair_rate: 3.0\n" +
                "  # 维修热量增长速率\n" +
                "  heat_rate: 0.1\n" +
                "  # 最大热量值\n" +
                "  max_heat: 1.0\n" +
                "  # 过热阈值（0.0-1.0）\n" +
                "  overheating_threshold: 0.8\n" +
                "  # 冷却时间（毫秒）\n" +
                "  cooldown_time: 3000\n" +
                "  # 过热持续时间（ticks）\n" +
                "  overheating_duration: 100\n" +
                "  # 挖掘速度（进度/秒）\n" +
                "  dig_speed: 0.2\n" +
                "  # 挖掘热量增长速率\n" +
                "  dig_heat_rate: 0.15\n" +
                "  # 最大挖掘范围\n" +
                "  max_range: 5\n\n" +
                "# 怪物仇恨系统\n" +
                "aggro_system:\n" +
                "  # 最大攻击距离\n" +
                "  max_aggro_range: 50.0\n" +
                "  # 目标切换冷却时间（毫秒）\n" +
                "  target_switch_cooldown: 5000\n" +
                "  # 寻路检查冷却时间（毫秒）\n" +
                "  path_check_cooldown: 2000\n" +
                "  # 最大寻路失败次数\n" +
                "  max_path_failures: 5\n" +
                "  # 寻路失败重置时间（毫秒）\n" +
                "  path_failure_reset: 15000\n" +
                "  # 卡住判定距离阈值\n" +
                "  stuck_distance_threshold: 0.3\n" +
                "  # 卡住判定时间阈值（毫秒）\n" +
                "  stuck_time_threshold: 3000\n\n" +
                "# 轮廓渲染配置\n" +
                "outline_render:\n" +
                "  # 粒子间距\n" +
                "  particle_spacing: 0.5\n" +
                "  # 最大渲染距离\n" +
                "  max_render_distance: 50\n" +
                "  # 吸附范围\n" +
                "  attach_range: 10.0\n" +
                "  # 颜色配置\n" +
                "  colors:\n" +
                "    valid: \"#00FF00\"   # 有效位置颜色\n" +
                "    invalid: \"#FF0000\" # 无效位置颜色\n" +
                "    placed: \"#FFFF00\"  # 已放置建筑颜色\n" +
                "    front: \"#FFFFFF\"   # 正面标记颜色\n\n" +
                "# 模板管理\n" +
                "template_manager:\n" +
                "  # 自动清理损坏模板\n" +
                "  auto_clean_corrupted: true\n" +
                "  # 模板文件压缩级别（1-9）\n" +
                "  compression_level: 6";
    }

    private void startOutlineRendering() {
        new BukkitRunnable() {
            @Override
            public void run() {
                itemListener.renderOutlines();
            }
        }.runTaskTimer(this, 0, 20);
    }

    public BuildingHealthSystem getHealthSystem() {
        return healthSystem;
    }

    public void removePermanentOutline(Location center) {
        placedOutlines.remove(center);
        if (itemListener != null) {
            itemListener.removeOutlineByLocation(center);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("TemplateManager 已禁用!");
    }

    private void startParticleRenderer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (BuildingHealthData data : healthSystem.getBuildings().values()) {
                    if (data.isCollapsing() || data.isGenerating()) continue;

                    // 修复：为每个建筑检查附近玩家
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getWorld().equals(data.getCenter().getWorld()) &&
                                player.getLocation().distance(data.getCenter()) <= 50) {
                            healthSystem.showHealthBar(data, true, true);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 80);
    }

    public TemplateManager getTemplateManager() {
        return templateManager;
    }

    public Selection getSelection(UUID playerId) {
        return selections.computeIfAbsent(playerId, k -> new Selection());
    }

    public long calculateChecksum(byte[] data) {
        if (data == null || data.length == 0) return 0;
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    public boolean templateExists(String name) {
        File templateDir = new File(getDataFolder(), "templates/" + name);
        return templateDir.exists() && templateDir.isDirectory();
    }

    public void addPlacedOutline(TemplateItemListener.OutlineData outline) {
        placedOutlines.put(outline.getLocation(), outline);
    }

    public void addPermanentOutline(Location center, int width, int height, int length, int rotation) {
        int rotatedWidth = RotationUtil.getRotatedWidth(rotation, width, length);
        int rotatedLength = RotationUtil.getRotatedLength(rotation, width, length);

        TemplateItemListener.OutlineData outline = new TemplateItemListener.OutlineData(
                "building",
                center,
                rotation,
                System.currentTimeMillis(),
                true,
                width,
                height,
                length,
                rotatedWidth,
                rotatedLength,
                null
        );
        outline.setPlaced(true);
        placedOutlines.put(center, outline);
    }

    public List<TemplateItemListener.OutlineData> getNearbyPlacedOutlines(Location location, double radius) {
        List<TemplateItemListener.OutlineData> nearby = new ArrayList<>();
        for (TemplateItemListener.OutlineData outline : placedOutlines.values()) {
            if (outline.getLocation().getWorld().equals(location.getWorld()) &&
                    outline.getLocation().distance(location) <= radius) {
                nearby.add(outline);
            }
        }
        return nearby;
    }

    private void safeWrite(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void cleanCorruptedTemplates() {
        File templatesDir = new File(getDataFolder(), "templates");
        if (!templatesDir.exists()) return;

        File[] templateFolders = templatesDir.listFiles(File::isDirectory);
        if (templateFolders == null) return;

        for (File folder : templateFolders) {
            File structureFile = new File(folder, "structure.bin");
            File configFile = new File(folder, "data.conf");

            if (!structureFile.exists() || structureFile.length() == 0) {
                getLogger().warning("检测到损坏的模板: " + folder.getName());
                try {
                    if (structureFile.exists()) Files.delete(structureFile.toPath());
                    if (configFile.exists()) Files.delete(configFile.toPath());
                    Files.delete(folder.toPath());
                    getLogger().info("已删除损坏的模板: " + folder.getName());
                } catch (IOException e) {
                    getLogger().warning("无法删除损坏的模板: " + folder.getName());
                }
            }
        }
    }

    public boolean generateBuilding(Player player, String templateName, Location location, int rotation) {
        if (!templateManager.templateExists(templateName)) {
            if (player != null) player.sendMessage(ChatColor.RED + "模板不存在: " + templateName);
            return false;
        }

        rotation = RotationUtil.normalizeRotation(rotation);
        BuildingTemplate template = templateManager.loadTemplate(templateName);
        if (template == null) {
            if (player != null) player.sendMessage(ChatColor.RED + "加载模板失败: " + templateName);
            return false;
        }

        player.sendMessage(ChatColor.GREEN + "正在生成建筑: " + templateName);
        template.generate(player, location, rotation);
        return true;
    }
}