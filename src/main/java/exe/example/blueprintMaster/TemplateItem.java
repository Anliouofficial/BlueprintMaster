package exe.example.blueprintMaster;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class TemplateItem {
    private static final NamespacedKey TEMPLATE_KEY = new NamespacedKey(JavaPlugin.getPlugin(TemplateManagerPlugin.class), "template_data");

    public static ItemStack createTemplateItem(String templateName, int rotation, boolean attachMode,
                                               double baseHealth, double armor, double healthPerBlock,
                                               String displayName) {
        ItemStack item = new ItemStack(Material.SCAFFOLDING);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.GOLD + "建筑模板: " + displayName);

        // 计算总血量
        double totalHealth = baseHealth + healthPerBlock;
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "模板: " + ChatColor.WHITE + templateName,
                ChatColor.GRAY + "旋转: " + ChatColor.WHITE + rotation + "°",
                ChatColor.GRAY + "吸附模式: " + (attachMode ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"),
                "",
                ChatColor.RED + "血量: " + totalHealth, // 显示总血量
                ChatColor.BLUE + "护甲: " + armor,     // 显示护甲值
                "",
                ChatColor.DARK_GRAY + "右键: 放置轮廓",
                ChatColor.DARK_GRAY + "Shift+右键: 生成建筑",
                ChatColor.DARK_GRAY + "Shift+左: 旋转90°",
                ChatColor.DARK_GRAY + "左键: 切换吸附模式"
        ));
        meta.setCustomModelData(1001);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(TEMPLATE_KEY, PersistentDataType.STRING,
                templateName + "|" + rotation + "|" + attachMode + "|" + baseHealth + "|" + armor + "|" + healthPerBlock);

        item.setItemMeta(meta);
        return item;
    }

    public static boolean isTemplateItem(ItemStack item) {
        if (item == null || item.getType() != Material.SCAFFOLDING) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(TEMPLATE_KEY, PersistentDataType.STRING);
    }

    public static String getTemplateName(ItemStack item) {
        if (!isTemplateItem(item)) return null;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? data.split("\\|")[0] : null;
    }

    public static int getRotation(ItemStack item) {
        if (!isTemplateItem(item)) return 0;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? Integer.parseInt(data.split("\\|")[1]) : 0;
    }

    public static boolean getAttachMode(ItemStack item) {
        if (!isTemplateItem(item)) return false;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? Boolean.parseBoolean(data.split("\\|")[2]) : false;
    }

    public static double getBaseHealth(ItemStack item) {
        if (!isTemplateItem(item)) return 0;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? Double.parseDouble(data.split("\\|")[3]) : 0;
    }

    public static double getArmor(ItemStack item) {
        if (!isTemplateItem(item)) return 0;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? Double.parseDouble(data.split("\\|")[4]) : 0;
    }

    public static double getHealthPerBlock(ItemStack item) {
        if (!isTemplateItem(item)) return 0;

        ItemMeta meta = item.getItemMeta();
        String data = meta.getPersistentDataContainer().get(TEMPLATE_KEY, PersistentDataType.STRING);
        return data != null ? Double.parseDouble(data.split("\\|")[5]) : 0;
    }

    public static ItemStack updateItem(ItemStack item, int rotation, boolean attachMode) {
        if (!isTemplateItem(item)) return item;

        // 确保旋转是90°倍数
        rotation = (rotation / 90) * 90;

        String templateName = getTemplateName(item);
        double baseHealth = getBaseHealth(item);
        double armor = getArmor(item);
        double healthPerBlock = getHealthPerBlock(item);
        return createTemplateItem(templateName, rotation, attachMode, baseHealth, armor, healthPerBlock, templateName);
    }
}