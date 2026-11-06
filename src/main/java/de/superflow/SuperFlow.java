package de.superflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SuperFlow extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey stackKey;   // Mob-Stackanzahl
    private NamespacedKey labelKey;   // Item hat von uns ein Label
    private final Set<UUID> frozenVillagers = new HashSet<>();
    private final Random random = new Random();

    // Einstellungen
    private boolean unlimitedTrades, freezeVillager;
    private boolean xpMerge, itemMerge, mobStack, disableAi, cull;
    private double xpRadius, itemRadius, mobRadius, cullRange;
    private int itemMax, mobMax;
    private boolean killAll;
    private int maxLooting, lootSamples;
    private final Set<EntityType> mobTypes = new HashSet<>();
    private final Set<SpawnReason> spawnReasons = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stackKey = new NamespacedKey(this, "stack");
        labelKey = new NamespacedKey(this, "label");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("vjob")).setExecutor(this);

        if (itemMerge) {
            long t = Math.max(1, getConfig().getLong("items.interval-ticks", 10));
            getServer().getScheduler().runTaskTimer(this, this::mergeItems, 40L, t);
        }
        if (cull) {
            long t = Math.max(1, getConfig().getLong("culling.interval-ticks", 10));
            getServer().getScheduler().runTaskTimer(this, this::cullTick, 40L, t);
        }
    }

    @Override
    public void onDisable() {
        // Villager-AI wiederherstellen
        for (World w : getServer().getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (frozenVillagers.contains(v.getUniqueId())) v.setAI(true);
            }
        }
        frozenVillagers.clear();
    }

    private void loadSettings() {
        var c = getConfig();
        unlimitedTrades = c.getBoolean("villager.unlimited-trades", true);
        freezeVillager = c.getBoolean("villager.freeze-while-trading", true);
        xpMerge = c.getBoolean("xp.merge", true);
        xpRadius = c.getDouble("xp.radius", 6.0);
        itemMerge = c.getBoolean("items.merge", true);
        itemRadius = c.getDouble("items.radius", 2.5);
        itemMax = c.getInt("items.max-stack", 5000);
        mobStack = c.getBoolean("mobs.stack", true);
        mobRadius = c.getDouble("mobs.radius", 8.0);
        mobMax = c.getInt("mobs.max-stack", 200);
        disableAi = c.getBoolean("mobs.disable-ai", true);
        killAll = c.getString("mobs.kill-mode", "all").equalsIgnoreCase("all");
        maxLooting = Math.max(0, c.getInt("mobs.max-looting", 10));
        lootSamples = Math.max(1, c.getInt("mobs.loot-samples", 25));
        cull = c.getBoolean("culling.enabled", true);
        cullRange = c.getDouble("culling.range", 48.0);

        mobTypes.clear();
        for (String s : c.getStringList("mobs.types")) {
            try { mobTypes.add(EntityType.valueOf(s.toUpperCase())); }
            catch (IllegalArgumentException ex) { getLogger().warning("Unbekannter Mob-Typ: " + s); }
        }
        spawnReasons.clear();
        for (String s : c.getStringList("mobs.spawn-reasons")) {
            try { spawnReasons.add(SpawnReason.valueOf(s.toUpperCase())); }
            catch (IllegalArgumentException ex) { getLogger().warning("Unbekannter Spawn-Grund: " + s); }
        }
    }

    // ------------------------------------------------------------------
    // 1) XP-Orbs zu grossen Klumpen
    // ------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onOrbSpawn(EntitySpawnEvent e) {
        if (!xpMerge || !(e.getEntity() instanceof ExperienceOrb orb)) return;
        Location loc = e.getLocation();
        for (ExperienceOrb other : loc.getNearbyEntitiesByType(ExperienceOrb.class, xpRadius)) {
            if (other == orb || !other.isValid()) continue;
            other.setExperience(other.getExperience() + orb.getExperience());
            e.setCancelled(true);
            return;
        }
    }

    // ------------------------------------------------------------------
    // 2) Items stacken (auch unstackbare, mit "15x" Label)
    // ------------------------------------------------------------------
    private boolean mergeable(ItemStack s) {
        if (s == null || s.getType().isAir()) return false;
        ItemMeta meta = s.getItemMeta();
        // beschädigte Items nicht mergen, sonst gibt es Pickup-Probleme
        return !(meta instanceof Damageable d && d.hasDamage());
    }

    private void mergeItems() {
        for (World w : getServer().getWorlds()) {
            for (Item a : w.getEntitiesByClass(Item.class)) {
                if (!a.isValid() || !a.isOnGround() || a.getPickupDelay() >= 32767) continue;
                ItemStack sa = a.getItemStack();
                if (!mergeable(sa)) continue;
                boolean changed = false;
                for (Item b : a.getLocation().getNearbyEntitiesByType(Item.class, itemRadius)) {
                    if (b == a || !b.isValid() || !b.isOnGround() || b.getPickupDelay() >= 32767) continue;
                    ItemStack sb = b.getItemStack();
                    if (!mergeable(sb) || !sa.isSimilar(sb)) continue;
                    if ((long) sa.getAmount() + sb.getAmount() > itemMax) continue;
                    sa.setAmount(sa.getAmount() + sb.getAmount());
                    b.remove();
                    changed = true;
                }
                if (changed) a.setItemStack(sa);
                updateItemLabel(a);
            }
        }
    }

    private void updateItemLabel(Item item) {
        ItemStack s = item.getItemStack();
        if (s.getAmount() > 1) {
            item.customName(Component.text(s.getAmount() + "x ", NamedTextColor.YELLOW)
                    .append(Component.translatable(s.translationKey()).color(NamedTextColor.WHITE)));
            item.setCustomNameVisible(true);
            item.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
        } else if (item.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) {
            item.customName(null);
            item.setCustomNameVisible(false);
            item.getPersistentDataContainer().remove(labelKey);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        Item item = e.getItem();
        getServer().getScheduler().runTask(this, () -> { if (item.isValid()) updateItemLabel(item); });
    }

    // ------------------------------------------------------------------
    // 3) Mobs stacken (Zombie x15 usw.), ohne KI, Looting bleibt erhalten
    // ------------------------------------------------------------------
    private int stackOf(Entity e) {
        Integer n = e.getPersistentDataContainer().get(stackKey, PersistentDataType.INTEGER);
        return n == null ? 0 : n;
    }

    private void setStack(LivingEntity le, int n) {
        le.getPersistentDataContainer().set(stackKey, PersistentDataType.INTEGER, n);
        le.customName(Component.text(n + "x ", NamedTextColor.YELLOW)
                .append(Component.translatable(le.getType().translationKey()).color(NamedTextColor.WHITE)));
        le.setCustomNameVisible(true);
    }

    private boolean sameVariant(LivingEntity a, LivingEntity b) {
        if (a instanceof Ageable x && b instanceof Ageable y && x.isAdult() != y.isAdult()) return false;
        if (a instanceof Zombie x && b instanceof Zombie y && x.isBaby() != y.isBaby()) return false;
        if (a instanceof Slime x && b instanceof Slime y && x.getSize() != y.getSize()) return false;
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (!mobStack) return;
        LivingEntity le = e.getEntity();
        if (!mobTypes.contains(le.getType()) || !spawnReasons.contains(e.getSpawnReason())) return;
        if (le.customName() != null) return;

        for (LivingEntity o : le.getLocation().getNearbyLivingEntities(mobRadius, mobRadius, mobRadius)) {
            if (o == le || !o.isValid() || o.getType() != le.getType()) continue;
            int n = stackOf(o);
            if (n <= 0 || n >= mobMax || !sameVariant(le, o)) continue;
            setStack(o, n + 1);
            e.setCancelled(true);
            return;
        }
        // neuer Stack beginnt mit 1
        setStack(le, 1);
        le.customName(null);
        le.setCustomNameVisible(false);
        if (disableAi) le.setAI(false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        int n = stackOf(le);
        if (n <= 1) return;

        if (!killAll) {
            // Einer stirbt, Rest bleibt als neuer Stack
            final int rest = n - 1;
            getServer().getScheduler().runTask(this, () ->
                    le.getWorld().spawnEntity(le.getLocation(), le.getType(), SpawnReason.CUSTOM, ent -> {
                        if (ent instanceof LivingEntity nl) {
                            if (disableAi) nl.setAI(false);
                            setStack(nl, rest);
                        }
                    }));
            return;
        }

        // Ganzer Stack stirbt: Loot (n-1)-mal zusätzlich würfeln, mit Looting des Killers
        Player killer = le.getKiller();
        int looting = 0;
        if (killer != null) {
            looting = killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
        }
        looting = Math.min(looting, maxLooting);
        if (le instanceof Lootable lootable && lootable.getLootTable() != null) {
            LootTable table = lootable.getLootTable();
            int rolls = n - 1;
            int samples = Math.min(rolls, lootSamples);   // nicht hunderte Male wuerfeln -> kein Lag
            double scale = (double) rolls / samples;      // Ergebnis hochrechnen

            Map<ItemStack, Double> sum = new LinkedHashMap<>();
            for (int i = 0; i < samples; i++) {
                LootContext ctx = new LootContext.Builder(le.getLocation())
                        .lootedEntity(le)
                        .killer(killer)
                        .build();
                for (ItemStack drop : table.populateLoot(random, ctx)) {
                    if (drop == null || drop.getType().isAir()) continue;
                    int amount = drop.getAmount();
                    // Bukkit ignoriert Looting -> selbst: bis zu +Level pro Wurf bei stapelbaren Drops
                    if (looting > 0 && drop.getMaxStackSize() > 1) {
                        amount += random.nextInt(looting + 1);
                    }
                    ItemStack key = drop.clone();
                    key.setAmount(1);
                    sum.merge(key, (double) amount, Double::sum);
                }
            }

            int entities = 0;
            for (Map.Entry<ItemStack, Double> en : sum.entrySet()) {
                double exact = en.getValue() * scale;
                long total = (long) Math.floor(exact);
                if (random.nextDouble() < exact - total) total++;   // faire Rundung
                ItemStack key = en.getKey();
                int max = Math.max(1, key.getMaxStackSize());
                while (total > 0 && entities < 64) {                 // harte Obergrenze gegen Lag
                    int part = (int) Math.min(max, total);
                    ItemStack out = key.clone();
                    out.setAmount(part);
                    e.getDrops().add(out);
                    total -= part;
                    entities++;
                }
            }
        }
        e.setDroppedExp(e.getDroppedExp() * n);
    }

    // ------------------------------------------------------------------
    // 4) Villager: unendlich traden, Menü bleibt offen (Villager steht still)
    // ------------------------------------------------------------------
    private void unlock(Villager v) {
        for (int i = 0; i < v.getRecipeCount(); i++) {
            MerchantRecipe r = v.getRecipe(i);
            r.setMaxUses(Integer.MAX_VALUE / 2);
            r.setUses(0);
            r.setDemand(0);
            r.setSpecialPrice(0);
            v.setRecipe(i, r);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantOpen(InventoryOpenEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi) || !(mi.getMerchant() instanceof Villager v)) return;
        if (unlimitedTrades) unlock(v);
        if (freezeVillager && v.hasAI()) {
            v.setAI(false);
            frozenVillagers.add(v.getUniqueId());
        }
    }

    @EventHandler
    public void onMerchantClose(InventoryCloseEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi) || !(mi.getMerchant() instanceof Villager v)) return;
        if (frozenVillagers.remove(v.getUniqueId())) v.setAI(true);
    }

    @EventHandler
    public void onTrade(PlayerTradeEvent e) {
        if (!unlimitedTrades || !(e.getVillager() instanceof Villager v)) return;
        // nach dem Trade sofort wieder entsperren
        getServer().getScheduler().runTask(this, () -> { if (v.isValid()) unlock(v); });
    }

    // /vjob reroll | /vjob <beruf>  -> Beruf/Trades sofort wechseln (kein Warten)
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Nur Spieler."); return true; }
        if (args.length != 1) { p.sendMessage("§e/vjob <reroll|beruf>"); return true; }
        Entity target = p.getTargetEntity(6);
        if (!(target instanceof Villager v)) { p.sendMessage("§cSchau einen Villager an."); return true; }

        Profession prof = v.getProfession();
        if (!args[0].equalsIgnoreCase("reroll")) {
            Profession wanted = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(args[0].toLowerCase()));
            if (wanted == null) { p.sendMessage("§cUnbekannter Beruf."); return true; }
            prof = wanted;
        }
        v.setProfession(prof);
        v.setVillagerLevel(1);
        v.setVillagerExperience(0);
        v.resetOffers();
        v.getRecipes(); // erzeugt neue Trades
        if (unlimitedTrades) unlock(v);
        p.sendMessage("§aVillager ist jetzt: " + prof.key().value() + " (neue Trades)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("reroll"));
        Registry.VILLAGER_PROFESSION.forEach(pr -> out.add(pr.key().value()));
        out.removeIf(x -> !x.startsWith(a[0].toLowerCase()));
        return out;
    }

    // ------------------------------------------------------------------
    // 5) Culling: Stack-Zahlen nur sichtbar, wenn Sichtlinie besteht
    // ------------------------------------------------------------------
    private boolean isLabeled(Entity en) {
        if (en instanceof Item it) return it.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE);
        return en instanceof LivingEntity && stackOf(en) > 0;
    }

    private final Map<UUID, Map<UUID, Integer>> misses = new HashMap<>();

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        misses.remove(e.getPlayer().getUniqueId());
    }

    private void cullTick() {
        for (Player p : getServer().getOnlinePlayers()) {
            Map<UUID, Integer> old = misses.getOrDefault(p.getUniqueId(), Map.of());
            Map<UUID, Integer> now = new HashMap<>();
            for (Entity en : p.getNearbyEntities(cullRange, cullRange, cullRange)) {
                if (!isLabeled(en)) continue;
                // nahe oder sichtbar -> immer zeigen (verhindert Flackern)
                if (p.getLocation().distanceSquared(en.getLocation()) < 36 || p.hasLineOfSight(en)) {
                    p.showEntity(this, en);
                    continue;
                }
                // erst nach 3 aufeinanderfolgenden Fehlversuchen ausblenden
                int m = old.getOrDefault(en.getUniqueId(), 0) + 1;
                now.put(en.getUniqueId(), m);
                if (m >= 3) p.hideEntity(this, en);
            }
            misses.put(p.getUniqueId(), now);
        }
    }
}
