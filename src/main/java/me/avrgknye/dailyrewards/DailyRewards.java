package me.avrgknye.dailyrewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class DailyRewards extends JavaPlugin implements Listener, CommandExecutor {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_TEST_DAY = 999;
    private static final int BOOKS_AFTER_DAY_8 = 1;
    private static final long JOIN_MESSAGE_DELAY_TICKS = 200L; // 200 ticks = 10 segundos
    private static final int CLAIM_SLOT = 31;
    private static final int[] REWARD_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private File dataFile;
    private YamlConfiguration data;

    public static class RewardMenu implements InventoryHolder {
        private Inventory inventory;
        private int day;
        private List<ItemStack> rewards;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getCommand("reward").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        if (args.length > 0) {
            if (!player.hasPermission("dailyrewards.admin")) {
                player.sendMessage(Component.text("No tienes permiso.", NamedTextColor.RED));
                return true;
            }
            handleAdmin(player, args);
            return true;
        }
        long now = System.currentTimeMillis();
        if (isOnCooldown(player, now)) {
            player.sendMessage(error());
            return true;
        }
        openMenu(player, nextDay(player, now));
        return true;
    }

    private void handleAdmin(Player player, String[] args) {
        String key = player.getUniqueId().toString();
        if (args[0].equalsIgnoreCase("reset")) {
            data.set(key, null);
            save();
            player.sendMessage(Component.text("Tus datos de recompensa fueron borrados.", NamedTextColor.YELLOW));
            return;
        }
        if (args[0].equalsIgnoreCase("dia") && args.length > 1) {
            try {
                int n = Integer.parseInt(args[1]);
                if (n < 1 || n > MAX_TEST_DAY) throw new NumberFormatException();
                if (n == 1) {
                    data.set(key, null);
                } else {
                    data.set(key + ".last", System.currentTimeMillis() - DAY_MS);
                    data.set(key + ".day", n - 1);
                }
                save();
                player.sendMessage(Component.text("Listo, tu próximo /reward será el día " + n + ".", NamedTextColor.YELLOW));
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Uso: /reward dia <1-" + MAX_TEST_DAY + ">", NamedTextColor.RED));
            }
            return;
        }
        player.sendMessage(Component.text("Uso: /reward reset | /reward dia <número>", NamedTextColor.RED));
    }

    private Component error() {
        return Component.text("Ya reclamaste tu recompensa, vuelve mañana a la misma hora", NamedTextColor.RED);
    }

    private boolean isOnCooldown(Player p, long now) {
        long last = data.getLong(p.getUniqueId() + ".last", 0L);
        return last > 0 && now - last < DAY_MS;
    }

    private int nextDay(Player p, long now) {
        String key = p.getUniqueId().toString();
        long last = data.getLong(key + ".last", 0L);
        int lastDay = data.getInt(key + ".day", 0);
        // más de 48 horas sin reclamar: la racha vuelve al día 1
        if (last == 0L || now - last >= 2 * DAY_MS) return 1;
        return lastDay + 1;
    }

    private void openMenu(Player player, int day) {
        RewardMenu holder = new RewardMenu();
        holder.day = day;
        holder.rewards = rewardsFor(day);

        Inventory inv = Bukkit.createInventory(holder, 36, Component.text("Recompensa " + day + " día(s)"));
        holder.inventory = inv;

        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.WHITE);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        for (int i = 0; i < holder.rewards.size() && i < REWARD_SLOTS.length; i++) {
            inv.setItem(REWARD_SLOTS[i], holder.rewards.get(i).clone());
        }
        inv.setItem(CLAIM_SLOT, named(Material.LIME_CONCRETE, "Reclamar recompensa", NamedTextColor.GREEN));
        player.openInventory(inv);
    }

    private ItemStack named(Material m, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RewardMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getSlot() != CLAIM_SLOT) return;
        claim(player, menu);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RewardMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        // se espera unos segundos para que el jugador alcance a iniciar sesión (AuthMe)
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) sendStatus(player);
        }, JOIN_MESSAGE_DELAY_TICKS);
    }

    private void sendStatus(Player player) {
        long now = System.currentTimeMillis();
        String key = player.getUniqueId().toString();
        long last = data.getLong(key + ".last", 0L);
        int lastDay = data.getInt(key + ".day", 0);

        // todavía no puede reclamar: avisa cuánto falta
        if (last > 0 && now - last < DAY_MS) {
            long ms = DAY_MS - (now - last);
            long hours = ms / 3_600_000L;
            long minutes = (ms % 3_600_000L) / 60_000L;
            player.sendMessage(Component.text(
                "Tu próxima recompensa estará lista en " + hours + " h " + minutes + " min.",
                NamedTextColor.YELLOW));
            return;
        }

        // pasaron más de 48 horas: perdió la racha
        if (last > 0 && now - last >= 2 * DAY_MS) {
            if (lastDay >= 2) {
                player.sendMessage(Component.text(
                    "Perdiste tu racha de " + lastDay + " días.", NamedTextColor.RED));
            }
            player.sendMessage(Component.text(
                "Tu recompensa del día 1 está lista. Reclámala con /reward", NamedTextColor.GREEN));
            return;
        }

        // recompensa lista (primera vez o racha activa)
        int day = nextDay(player, now);
        player.sendMessage(Component.text(
            "Tu recompensa del día " + day + " está lista. Reclámala con /reward", NamedTextColor.GREEN));

        if (last > 0) {
            long msLeft = 2 * DAY_MS - (now - last);
            String left = msLeft >= 3_600_000L ? (msLeft / 3_600_000L) + " h" : "menos de 1 hora";
            player.sendMessage(Component.text(
                "Te quedan " + left + " para reclamarla sin perder tu racha.", NamedTextColor.GOLD));
        }
    }

    private void claim(Player player, RewardMenu menu) {
        long now = System.currentTimeMillis();
        if (isOnCooldown(player, now)) {
            getServer().getScheduler().runTask(this, () -> player.closeInventory());
            player.sendMessage(error());
            return;
        }

        String key = player.getUniqueId().toString();
        data.set(key + ".last", now);
        data.set(key + ".day", menu.day);
        save();

        for (ItemStack item : menu.rewards) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(item.clone());
            for (ItemStack rest : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        getServer().getScheduler().runTask(this, () -> player.closeInventory());
        player.sendMessage(Component.text("¡Reclamaste tu recompensa del día " + menu.day + "!", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("No se pudo guardar data.yml: " + ex.getMessage());
        }
    }

    // ---------- Tabla de recompensas ----------

    private List<ItemStack> rewardsFor(int day) {
        List<ItemStack> l = new ArrayList<>();
        switch (day) {
            case 1 -> {
                add(l, Material.DIAMOND, 1);
                add(l, Material.IRON_INGOT, 10);
                add(l, Material.COOKED_BEEF, 5);
            }
            case 2 -> {
                add(l, Material.DIAMOND, 1);
                add(l, Material.GOLDEN_APPLE, 1);
                add(l, Material.COOKED_BEEF, 10);
                add(l, Material.BOOK, 5);
                add(l, Material.IRON_INGOT, 10);
            }
            case 3 -> {
                add(l, Material.DIAMOND, 2);
                add(l, Material.GOLDEN_APPLE, 1);
                add(l, Material.COOKED_BEEF, 10);
                add(l, Material.BOOK, 5);
                add(l, Material.IRON_INGOT, 15);
            }
            case 4 -> {
                add(l, Material.DIAMOND, 2);
                add(l, Material.GOLDEN_APPLE, 2);
                add(l, Material.COOKED_BEEF, 10);
                add(l, Material.BOOK, 10);
                add(l, Material.IRON_INGOT, 20);
            }
            case 5 -> {
                armor(l, 0);
                add(l, Material.COOKED_BEEF, 10);
                add(l, Material.DIAMOND, 1);
                add(l, Material.BOOK, 5);
                add(l, Material.IRON_INGOT, 10);
            }
            case 6 -> {
                armor(l, 0);
                add(l, Material.COOKED_BEEF, 10);
                add(l, Material.GOLDEN_APPLE, 2);
                add(l, Material.DIAMOND, 2);
                add(l, Material.BOOK, 5);
                add(l, Material.IRON_INGOT, 15);
            }
            case 7 -> {
                armor(l, 1);
                l.add(enchantedBook(1));
                add(l, Material.GOLDEN_APPLE, 2);
                add(l, Material.BOOK, 5);
                add(l, Material.VILLAGER_SPAWN_EGG, 2);
                add(l, Material.EMERALD, 10);
                add(l, Material.IRON_INGOT, 10);
            }
            case 8 -> {
                l.add(enchantedBook(2));
                add(l, Material.VILLAGER_SPAWN_EGG, 3);
                add(l, Material.ENCHANTED_GOLDEN_APPLE, 1);
                add(l, Material.BOOK, 10);
                add(l, Material.EMERALD, 10);
                add(l, Material.DIAMOND, 5);
            }
            default -> {
                // día 9 en adelante: libros encantados al azar
                for (int i = 0; i < BOOKS_AFTER_DAY_8; i++) {
                    l.add(randomEnchantedBook());
                }
            }
        }
        return l;
    }

    private void add(List<ItemStack> l, Material m, int amount) {
        l.add(new ItemStack(m, amount));
    }

    private void armor(List<ItemStack> l, int protectionLevel) {
        Material[] pieces = {
            Material.IRON_HELMET, Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS, Material.IRON_BOOTS
        };
        Enchantment prot = protection();
        for (Material m : pieces) {
            ItemStack it = new ItemStack(m);
            if (protectionLevel > 0 && prot != null) it.addEnchantment(prot, protectionLevel);
            l.add(it);
        }
    }

    private ItemStack enchantedBook(int level) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) it.getItemMeta();
        Enchantment prot = protection();
        if (prot != null) meta.addStoredEnchant(prot, level, true);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack randomEnchantedBook() {
        List<Enchantment> pool = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) {
            String name = e.getKey().getKey();
            if (name.endsWith("_curse")) continue; // sin maldiciones
            pool.add(e);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Enchantment chosen = pool.get(rnd.nextInt(pool.size()));
        int level = rnd.nextInt(chosen.getMaxLevel()) + 1;

        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) it.getItemMeta();
        meta.addStoredEnchant(chosen, level, true);
        it.setItemMeta(meta);
        return it;
    }

    private Enchantment protection() {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("protection"));
    }
}
