package ru.frostworld.roulette;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Integer> playerTickets = new HashMap<>();
    private final List<Prize> prizes = new ArrayList<>();
    private String menuTitle;
    private String openCmd;
    
    // 12 слотов, образующих ИДЕАЛЬНОЕ геометрическое кольцо в инвентаре 6х9 (54 слота)!
    private final int[] wheelSlots = {19, 10, 1, 2, 3, 12, 21, 30, 39, 38, 37, 28};
    private final Map<Integer, ItemStack> originalItems = new HashMap<>();
    private final int buttonSlot = 22; // Кнопка запуска строго в центре колеса

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigData();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand(openCmd).setExecutor(this);
        getCommand("giveroulettekey").setExecutor(this);
    }

    private void loadConfigData() {
        prizes.clear();
        FileConfiguration config = getConfig();
        menuTitle = config.getString("menu-title", "&6&lКолесо Фортуны").replace("&", "§");
        openCmd = config.getString("open-command", "spin");
        
        for (String key : config.getConfigurationSection("prizes").getKeys(false)) {
            Material mat = Material.valueOf(config.getString("prizes." + key + ".material"));
            String name = config.getString("prizes." + key + ".name").replace("&", "§");
            int chance = config.getInt("prizes." + key + ".chance");
            List<String> cmds = config.getStringList("prizes." + key + ".commands");
            prizes.add(new Prize(mat, name, chance, cmds));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase(openCmd) && sender instanceof Player) {
            openGUI((Player) sender);
            return true;
        }
        
        if (cmd.getName().equalsIgnoreCase("giveroulettekey")) {
            if (!sender.hasPermission("fwroulette.admin")) {
                sender.sendMessage("§cУ вас нет прав!");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cИспользуйте: /giveroulettekey [ник] [кол-во]");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cИгрок не найден!");
                return true;
            }
            int amount = Integer.parseInt(args[1]);
            playerTickets.put(target.getUniqueId(), playerTickets.getOrDefault(target.getUniqueId(), 0) + amount);
            sender.sendMessage("§aВы выдали §e" + amount + " §aбилетов игроку §e" + target.getName());
            target.sendMessage("§6[FW] §fВам выдано §e" + amount + " §fбилетов! Нажмите §a/spin §fчтобы покрутить.");
            return true;
        }
        return false;
    }

    private void openGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, menuTitle);
        int tickets = playerTickets.getOrDefault(p.getUniqueId(), 0);
        
        // Заполняем весь фон чёрным стеклом
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        
        // Кнопка запуска строго в центре геометрического круга (слот 22)
        ItemStack start = createItem(Material.LIME_STAINED_GLASS_PANE, "§a§l[ КРУТИТЬ КОЛЕСО ]");
        ItemMeta meta = start.getItemMeta();
        meta.setLore(Arrays.asList("§7Стоимость: §e1 Билет", "§7Ваши билеты: §e" + tickets));
        start.setItemMeta(meta);
        inv.setItem(buttonSlot, start);

        // Раскладываем призы по идеальному кругу
        for (int slot : wheelSlots) {
            inv.setItem(slot, getRandomPrize().toItemStack());
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals(menuTitle)) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            
            if (e.getRawSlot() == buttonSlot && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.LIME_STAINED_GLASS_PANE) {
                int tickets = playerTickets.getOrDefault(p.getUniqueId(), 0);
                
                // ЖЁСТКАЯ ПРОВЕРКА НА КЛЮЧИ! Без них крутить нельзя!
                if (tickets <= 0) {
                    p.sendMessage("§6[FW] §cУ вас нет билетов для прокрутки колеса!");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    p.closeInventory();
                    return;
                }
                
                // Списываем 1 билет
                playerTickets.put(p.getUniqueId(), tickets - 1);
                
                // Меняем кнопку на статус кручения
                e.getInventory().setItem(buttonSlot, createItem(Material.RED_STAINED_GLASS_PANE, "§c§lКОЛЕСО КРУТИТСЯ..."));
                startWheelRoll(p, e.getInventory());
            }
        }
    }

    private void startWheelRoll(Player p, Inventory inv) {
        originalItems.clear();
        for (int slot : wheelSlots) {
            originalItems.put(slot, inv.getItem(slot));
        }

        new BukkitRunnable() {
            int ticks = 0;
            int delay = 1;
            int maxTicks = 60; 
            int currentIndex = 0;

            @Override
            public void run() {
                if (!p.getOpenInventory().getTitle().equals(menuTitle)) { this.cancel(); return; }
                ticks++;
                
                if (ticks > 25) delay = 2;
                if (ticks > 45) delay = 4;
                if (ticks > 52) delay = 8;

                if (ticks % delay == 0) {
                    int prevSlot = wheelSlots[currentIndex];
                    inv.setItem(prevSlot, originalItems.get(prevSlot));

                    currentIndex = (currentIndex + 1) % wheelSlots.length;
                    int currentSlot = wheelSlots[currentIndex];

                    inv.setItem(currentSlot, createItem(Material.LIME_STAINED_GLASS_PANE, "§e§l✦ БАРАБАН КРУТИТСЯ ✦"));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
                }

                if (ticks >= maxTicks) {
                    this.cancel();
                    int finalSlot = wheelSlots[currentIndex];
                    ItemStack winItem = originalItems.get(finalSlot);
                    Prize won = findPrize(winItem);
                    
                    if (won != null) {
                        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.3f);
                        for (String cmd : won.commands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
                        }
                    }
                    p.closeInventory();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private Prize getRandomPrize() {
        int total = prizes.stream().mapToInt(p -> p.chance).sum();
        int rand = new Random().nextInt(total);
        int cur = 0;
        for (Prize p : prizes) {
            cur += p.chance;
            if (rand < cur) return p;
        }
        return prizes.get(0);
    }

    private Prize findPrize(ItemStack is) {
        if (is == null || !is.hasItemMeta()) return null;
        for (Prize p : prizes) {
            if (is.getItemMeta().getDisplayName().equals(p.name)) return p;
        }
        return null;
    }

    private ItemStack createItem(Material mat, String name) {
        ItemStack is = new ItemStack(mat);
        ItemMeta im = is.getItemMeta();
        im.setDisplayName(name);
        is.setItemMeta(im);
        return is;
    }

    private static class Prize {
        Material mat; String name; int chance; List<String> commands;
        Prize(Material m, String n, int c, List<String> cmd) { this.mat = m; this.name = n; this.chance = c; this.commands = cmd; }
        ItemStack toItemStack() {
            ItemStack is = new ItemStack(mat); ItemMeta im = is.getItemMeta();
            im.setDisplayName(name); is.setItemMeta(im); return is;
        }
    }
}
