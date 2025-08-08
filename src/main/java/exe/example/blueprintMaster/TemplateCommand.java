package exe.example.blueprintMaster;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TemplateCommand implements CommandExecutor, TabCompleter {
    private final TemplateManagerPlugin plugin;
    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "reload", "repairtool", "save", "wand", "item", "health", "delete", "create"
    );

    public TemplateCommand(TemplateManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令");
            return true;
        }

        Player player = (Player) sender;
        if (args.length < 1) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(player);
                break;
            case "repairtool":
                giveRepairTool(player);
                break;
            case "save":
                handleSave(player, args);
                break;
            case "wand":
                giveSelectionWand(player);
                break;
            case "item":
                handleItem(player, args);
                break;
            case "health":
                handleHealth(player);
                break;
            case "delete":
                handleDelete(player, args);
                break;
            case "create":
                handleCreate(player, args);
                break;
            default:
                player.sendMessage(ChatColor.RED + "未知子命令");
                sendUsage(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        else if (args.length == 2 && "item".equalsIgnoreCase(args[0])) {
            List<String> templateNames = plugin.getTemplateManager().getTemplateNames();
            List<String> matches = new ArrayList<>();
            for (String name : templateNames) {
                if (name.toLowerCase().startsWith(args[1].toLowerCase())) {
                    matches.add(name);
                }
            }
            return matches;
        }
        else if (args.length == 2 && "create".equalsIgnoreCase(args[0])) {
            List<String> templateNames = plugin.getTemplateManager().getTemplateNames();
            List<String> matches = new ArrayList<>();
            for (String name : templateNames) {
                if (name.toLowerCase().startsWith(args[1].toLowerCase())) {
                    matches.add(name);
                }
            }
            return matches;
        }
        return Collections.emptyList();
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage(ChatColor.RED + "用法: /template create <模板名称> <x> <y> <z> [旋转角度]");
            return;
        }

        // 解析模板名称
        String templateName = args[1];

        // 验证模板是否存在
        if (!plugin.getTemplateManager().templateExists(templateName)) {
            player.sendMessage(ChatColor.RED + "模板不存在: " + templateName);
            return;
        }

        // 解析坐标参数
        double x, y, z;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "坐标必须是有效数字");
            return;
        }

        // 解析旋转角度（可选）
        int rotation = 0;
        if (args.length >= 6) {
            try {
                rotation = Integer.parseInt(args[5]);
                // 规范化旋转角度为90度倍数
                rotation = (rotation / 90) * 90;
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "旋转角度必须是整数，使用默认值0");
            }
        }

        // 创建位置对象
        Location location = new Location(player.getWorld(), x, y, z);

        // 加载模板并生成建筑
        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(templateName);
        if (template == null) {
            player.sendMessage(ChatColor.RED + "加载模板失败: " + templateName);
            return;
        }

        player.sendMessage(ChatColor.GREEN + "正在生成建筑: " + templateName);
        template.generate(player, location, rotation);
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("template.admin")) {
            player.sendMessage(ChatColor.RED + "你没有执行此命令的权限!");
            return;
        }

        plugin.reloadConfig();
        player.sendMessage(ChatColor.GREEN + "配置重载完成!");
    }

    private void handleSave(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "用法: /template save <名称> [显示名称]");
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String displayName = name; // 默认显示名与保存名相同

        // 检查是否指定了显示名
        if (name.contains("|")) {
            String[] parts = name.split("\\|", 2);
            name = parts[0].trim();
            displayName = parts[1].trim();
        }

        // 解析爆炸伤害属性
        boolean explosionDamage = false;
        if (args.length >= 3 && args[args.length - 1].equalsIgnoreCase("explode")) {
            explosionDamage = true;
            name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        }

        player.sendMessage(ChatColor.GREEN + "正在保存模板: " + displayName);
        plugin.getTemplateManager().saveTemplate(player, name, displayName, explosionDamage);
    }

    private void handleItem(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "用法: /template item <名称>");
            return;
        }

        String templateName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (!plugin.getTemplateManager().templateExists(templateName)) {
            player.sendMessage(ChatColor.RED + "模板不存在: " + templateName);
            return;
        }

        BuildingTemplate template = plugin.getTemplateManager().loadTemplate(templateName);
        if (template == null) {
            player.sendMessage(ChatColor.RED + "加载模板失败");
            return;
        }

        ItemStack templateItem = TemplateItem.createTemplateItem(
                templateName,
                0,
                false,
                template.getBaseHealth(),
                template.getArmor(),
                template.getHealthPerBlock(),
                template.getDisplayName()
        );

        player.getInventory().addItem(templateItem);
        player.sendMessage(ChatColor.GREEN + "已获得模板物品: " + templateName);
    }

    private void giveRepairTool(Player player) {
        ItemStack tool = new ItemStack(Material.LIGHTNING_ROD);
        ItemMeta meta = tool.getItemMeta();

        meta.setDisplayName(ChatColor.GOLD + "建筑维修工具");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "右键建筑开始/停止维修",
                ChatColor.GRAY + "维修时工具会逐渐过热",
                ChatColor.RED + "过热时会使你减速",
                ChatColor.YELLOW + "多人协同维修可提高效率"
        ));

        meta.setUnbreakable(true);
        tool.setItemMeta(meta);

        player.getInventory().addItem(tool);
        player.sendMessage(ChatColor.GREEN + "已获得建筑维修工具");
    }

    private void handleHealth(Player player) {
        BuildingHealthSystem healthSystem = plugin.getHealthSystem();
        Location playerLoc = player.getLocation();

        BuildingHealthData nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (BuildingHealthData data : healthSystem.getBuildings().values()) {
            double distance = playerLoc.distance(data.getBottomCenter());
            if (distance < minDistance) {
                minDistance = distance;
                nearest = data;
            }
        }

        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "附近没有建筑");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "=== 建筑血量信息 ===");
        player.sendMessage(ChatColor.GREEN + "位置: " + formatLocation(nearest.getCenter()));
        player.sendMessage(ChatColor.RED + "血量: " +
                String.format("%.1f/%.1f", nearest.getHealth(), nearest.getMaxHealth()));
        player.sendMessage(ChatColor.BLUE + "护甲: " + nearest.getArmor());
        player.sendMessage(ChatColor.LIGHT_PURPLE + "状态: " +
                (nearest.isCollapsing() ? "崩塌中" :
                        nearest.isGenerating() ? "生成中" : "正常"));

        healthSystem.showHealthBar(nearest, true, true);
    }

    private void handleDelete(Player player, String[] args) {
        BuildingHealthSystem healthSystem = plugin.getHealthSystem();
        Location playerLoc = player.getLocation();

        BuildingHealthData nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (BuildingHealthData data : healthSystem.getBuildings().values()) {
            double distance = playerLoc.distance(data.getBottomCenter());
            if (distance < minDistance) {
                minDistance = distance;
                nearest = data;
            }
        }

        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "附近没有建筑");
            return;
        }

        if (!nearest.getOwnerId().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "您不是该建筑的所有者");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            healthSystem.removeBuilding(player, nearest.getBuildingId());
        } else {
            player.sendMessage(ChatColor.GOLD + "即将删除建筑:");
            player.sendMessage(ChatColor.YELLOW + "位置: " + formatLocation(nearest.getCenter()));
            player.sendMessage(ChatColor.YELLOW + "输入 " + ChatColor.RED + "/template delete confirm" +
                    ChatColor.YELLOW + " 确认删除");

            final BuildingHealthData finalNearest = nearest;
            final Player finalPlayer = player;

            new BukkitRunnable() {
                int count = 0;
                @Override
                public void run() {
                    if (count++ > 20) cancel();
                    finalPlayer.spawnParticle(Particle.FLAME,  // 修复：使用FLAME粒子
                            finalNearest.getCenter().add(0, finalNearest.getHeight()/2, 0),
                            20, 1, 1, 1, 0.1);
                }
            }.runTaskTimer(plugin, 0, 10);
        }
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)",
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "模板管理命令:");
        player.sendMessage(ChatColor.YELLOW + "/template wand - 获取选区工具");
        player.sendMessage(ChatColor.YELLOW + "/template save <名称> - 保存选区为模板");
        player.sendMessage(ChatColor.YELLOW + "/template item <名称> - 获取模板物品");
        player.sendMessage(ChatColor.YELLOW + "/template health - 查看附近建筑血量");
        player.sendMessage(ChatColor.YELLOW + "/template delete - 删除附近建筑");
        player.sendMessage(ChatColor.YELLOW + "/template repairtool - 获取建筑维修工具");
        player.sendMessage(ChatColor.YELLOW + "/template reload - 重载插件配置 (管理员)");
        player.sendMessage(ChatColor.YELLOW + "/template create <模板> <x> <y> <z> [角度] - 在指定位置生成建筑");
    }

    private void giveSelectionWand(Player player) {
        ItemStack wand = new ItemStack(Material.STICK);
        ItemMeta meta = wand.getItemMeta();

        meta.setDisplayName(ChatColor.GREEN + "选区工具");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "左键：设置第一点",
                ChatColor.GRAY + "右键：设置第二点"
        ));
        wand.setItemMeta(meta);

        player.getInventory().addItem(wand);
        player.sendMessage(ChatColor.GREEN + "已获得选区工具");
    }
}