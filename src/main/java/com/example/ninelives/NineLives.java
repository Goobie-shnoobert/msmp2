package com.example.ninelives;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NineLives extends JavaPlugin implements Listener {

    // ===== Tweak these =====
    private static final int MAX_LIVES = 9;
    private static final int REVIVE_LIVES = 3; // lives a revived player comes back with

    // Milk bucket (golden apple / notch apple hybrid). Durations are in ticks (20 = 1 second).
    private static final int MILK_REGEN_TICKS = 15 * 20;
    private static final int MILK_REGEN_LEVEL = 3;       // Regeneration III
    private static final int MILK_ABSORB_TICKS = 120 * 20;
    private static final int MILK_ABSORB_LEVEL = 7;      // Absorption VII
    private static final int MILK_FIRE_RES_TICKS = 60 * 20;
    private static final int MILK_FOOD = 6;
    private static final float MILK_SATURATION = 12f;

    // Cooked fish (plain golden apple effects)
    private static final int FISH_REGEN_TICKS = 5 * 20;
    private static final int FISH_REGEN_LEVEL = 2;       // Regeneration II
    private static final int FISH_ABSORB_TICKS = 120 * 20;
    private static final int FISH_ABSORB_LEVEL = 1;      // Absorption I
    // =======================

    private File livesFile;
    private YamlConfiguration livesData;
    private NamespacedKey reviveKey;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        livesFile = new File(getDataFolder(), "lives.yml");
        livesData = YamlConfiguration.loadConfiguration(livesFile);
        reviveKey = new NamespacedKey(this, "lucky_collar");

        getServer().getPluginManager().registerEvents(this, this);
        registerCollarRecipe();
    }

    // ---------- Lives storage ----------

    private int getLives(UUID id) {
        return livesData.getInt(id.toString(), MAX_LIVES);
    }

    private void setLives(UUID id, int lives) {
        livesData.set(id.toString(), lives);
        try {
            livesData.save(livesFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save lives.yml: " + ex.getMessage());
        }
    }

    // ---------- Deaths ----------
    // A Totem of Undying save never fires PlayerDeathEvent, so totems cost no life.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        int left = getLives(p.getUniqueId()) - 1;
        setLives(p.getUniqueId(), left);

        if (left > 0) {
            p.sendMessage(ChatColor.RED + "You lost a life! Lives left: " + left);
            return;
        }

        String name = p.getName();
        String reason = "You ran out of lives! A Lucky Collar can bring you back.";
        Bukkit.broadcastMessage(ChatColor.DARK_RED + name + " has lost their final life!");
        getServer().getScheduler().runTask(this, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason));
    }

    // ---------- Milk + fish ----------

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Material type = e.getItem().getType();

        if (type == Material.MILK_BUCKET) {
            // Vanilla clears effects right after this event, so snapshot now and restore next tick.
            List<PotionEffect> before = new ArrayList<>(p.getActivePotionEffects());
            getServer().getScheduler().runTask(this, () -> {
                p.addPotionEffects(before);
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, MILK_REGEN_TICKS, MILK_REGEN_LEVEL - 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, MILK_ABSORB_TICKS, MILK_ABSORB_LEVEL - 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, MILK_FIRE_RES_TICKS, 0));
                p.setFoodLevel(Math.min(20, p.getFoodLevel() + MILK_FOOD));
                p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + MILK_SATURATION));
            });
        } else if (type == Material.COOKED_COD || type == Material.COOKED_SALMON) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, FISH_REGEN_TICKS, FISH_REGEN_LEVEL - 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, FISH_ABSORB_TICKS, FISH_ABSORB_LEVEL - 1));
        }
    }

    // ---------- Lucky Collar ----------

    private ItemStack luckyCollar() {
        ItemStack item = new ItemStack(Material.BELL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Lucky Collar");
        meta.setLore(List.of(
                ChatColor.GRAY + "A cat's lucky collar. Hold it and",
                ChatColor.GRAY + "run /revive <player> to give them",
                ChatColor.GRAY + "another one of their nine lives."));
        meta.getPersistentDataContainer().set(reviveKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isLuckyCollar(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(reviveKey, PersistentDataType.BYTE);
    }

    // The collar is a bell item, so stop it from being placed as a bell block.
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLuckyCollar(e.getItemInHand())) e.setCancelled(true);
    }

    private void registerCollarRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(reviveKey, luckyCollar());
        recipe.shape(" B ", "LTL", " S ");
        recipe.setIngredient('B', Material.BELL);
        recipe.setIngredient('L', Material.LEAD);
        recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
        recipe.setIngredient('S', Material.COOKED_SALMON);
        Bukkit.addRecipe(recipe);
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("lives")) {
            OfflinePlayer target;
            if (args.length > 0) {
                target = Bukkit.getOfflinePlayer(args[0]);
            } else if (sender instanceof Player p) {
                target = p;
            } else {
                sender.sendMessage("Usage: /lives <player>");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "" + target.getName() + " has "
                    + Math.max(0, getLives(target.getUniqueId())) + " lives left.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("revive")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            if (args.length == 0) return false;

            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!isLuckyCollar(hand)) {
                p.sendMessage(ChatColor.RED + "Hold a Lucky Collar in your main hand.");
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (getLives(target.getUniqueId()) > 0) {
                p.sendMessage(ChatColor.RED + args[0] + " still has lives left.");
                return true;
            }

            hand.setAmount(hand.getAmount() - 1);
            setLives(target.getUniqueId(), REVIVE_LIVES);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + args[0]);
            Bukkit.broadcastMessage(ChatColor.GREEN + args[0] + " used one of their nine lives... again! "
                    + "Revived by " + p.getName() + " with " + REVIVE_LIVES + " lives.");
            // Meow + hearts at the reviver so everyone nearby sees it.
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_CAT_AMBIENT, 1.0f, 1.0f);
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.5, 0), 12, 0.5, 0.5, 0.5);
            return true;
        }
        return false;
    }
}
