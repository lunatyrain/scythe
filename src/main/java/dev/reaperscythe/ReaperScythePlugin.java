package dev.reaperscythe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Soul Reaper: a PvP scythe with soul stacks, a spinning sweep and a soul-chain pull. */
public final class ReaperScythePlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final class Souls {
        int count;
        int lastHit;
    }

    private NamespacedKey scytheKey;
    private final Map<UUID, Souls> souls = new HashMap<>();
    private final Map<UUID, Integer> sweepReady = new HashMap<>();
    private final Map<UUID, Integer> gripReady = new HashMap<>();
    private boolean abilityDamage;   // true while our own ability damage is being dealt

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scytheKey = new NamespacedKey(this, "soul_reaper");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("scythe");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::hud, 10L, 5L);
    }

    // --------------------------------------------------------------------- item

    ItemStack scythe() {
        ItemStack it = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("reaperscythe:soul_scythe"));
        m.displayName(Component.text("Soul Reaper", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                line("Hits on players harvest souls: more damage and lifesteal.", NamedTextColor.GRAY),
                line("Right click: Reaping Sweep - a spinning slash around you.", NamedTextColor.DARK_AQUA),
                line("Shift + right click: Death Grip - yank a player to you.", NamedTextColor.DARK_AQUA),
                line("5 souls: the next Sweep is empowered.", NamedTextColor.GOLD)));
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(this, "scythe_damage"),
                getConfig().getDouble("weapon.attack-damage", 6.0), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(this, "scythe_speed"),
                getConfig().getDouble("weapon.attack-speed", -2.4), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(new NamespacedKey(this, "scythe_reach"),
                getConfig().getDouble("weapon.extra-reach", 0.75), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(scytheKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isScythe(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(scytheKey, PersistentDataType.BYTE);
    }

    private boolean holding(Player p) {
        return isScythe(p.getInventory().getItemInMainHand());
    }

    // -------------------------------------------------------------------- souls

    private int maxSouls() {
        return getConfig().getInt("souls.max", 5);
    }

    private Souls soulsOf(Player p) {
        Souls s = souls.computeIfAbsent(p.getUniqueId(), k -> new Souls());
        int decay = getConfig().getInt("souls.decay-seconds", 8) * 20;
        if (s.count > 0 && Bukkit.getCurrentTick() - s.lastHit > decay) s.count = 0;
        return s;
    }

    private static boolean hittable(LivingEntity le, Player self) {
        if (le == self || le instanceof ArmorStand || !le.isValid()) return false;
        if (le instanceof Player tp) return tp.getGameMode() == GameMode.SURVIVAL || tp.getGameMode() == GameMode.ADVENTURE;
        return true;
    }

    // ------------------------------------------------------------------- events

    /** Melee hits on players harvest souls, add damage and steal life. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (abilityDamage) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof Player target)) return;
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!holding(p)) return;

        Souls s = soulsOf(p);
        double bonus = 1 + getConfig().getDouble("souls.damage-bonus-percent", 4) / 100.0 * s.count;
        e.setDamage(e.getDamage() * bonus);

        double heal = e.getFinalDamage() * getConfig().getDouble("souls.lifesteal-percent", 8) / 100.0;
        heal(p, heal);

        if (s.count < maxSouls()) s.count++;
        s.lastHit = Bukkit.getCurrentTick();
        Location at = target.getLocation().add(0, 1, 0);
        target.getWorld().spawnParticle(Particle.SOUL, at, 8, 0.3, 0.5, 0.3, 0.03);
        p.playSound(p.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1f, 1.2f + 0.1f * s.count);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!holding(p)) return;
        e.setCancelled(true);          // no tilling soil with the scythe
        if (p.isSneaking()) grip(p);
        else sweep(p);
    }

    /** Killing a player with the scythe: souls, absorption and a fresh Reaping Sweep. */
    @EventHandler
    public void onKill(PlayerDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        souls.remove(e.getEntity().getUniqueId());
        if (killer == null || !holding(killer)) return;
        Souls s = soulsOf(killer);
        s.count = Math.min(maxSouls(), s.count + getConfig().getInt("kill-reward.souls", 2));
        s.lastHit = Bukkit.getCurrentTick();
        sweepReady.remove(killer.getUniqueId());
        int abs = getConfig().getInt("kill-reward.absorption-seconds", 10) * 20;
        if (abs > 0) killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, abs, 1));
        killer.getWorld().spawnParticle(Particle.SCULK_SOUL, killer.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.05);
        killer.playSound(killer.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.8f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        souls.remove(id);
        sweepReady.remove(id);
        gripReady.remove(id);
    }

    // ---------------------------------------------------------------- abilities

    private void heal(Player p, double amount) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max != null ? max.getValue() : 20;
        p.setHealth(Math.min(cap, p.getHealth() + amount));
    }

    /** Right click: a spinning slash around you. At full souls it is bigger, withers and heals. */
    private void sweep(Player p) {
        int now = Bukkit.getCurrentTick();
        if (now < sweepReady.getOrDefault(p.getUniqueId(), 0)) return;

        Souls s = soulsOf(p);
        boolean empowered = s.count >= maxSouls();
        double radius = empowered ? getConfig().getDouble("sweep.empowered-radius", 6.0)
                : getConfig().getDouble("sweep.radius", 4.0);
        double dmg = getConfig().getDouble("sweep.damage", 6.0) + 0.5 * s.count
                + (empowered ? getConfig().getDouble("sweep.empowered-bonus-damage", 3.0) : 0);
        sweepReady.put(p.getUniqueId(), now + getConfig().getInt("sweep.cooldown-seconds", 8) * 20);
        if (empowered) s.count = 0;

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.7f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.7f, empowered ? 0.6f : 1.2f);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                Location c = p.getLocation().add(0, 1.0, 0);
                double ang = t * (Math.PI * 2 / 8.0);
                for (double r = 1; r <= radius; r += 0.75) {
                    Location pt = c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                    p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, pt, 2, 0.05, 0.05, 0.05, 0.01);
                }
                Location tip = c.clone().add(Math.cos(ang) * (radius - 0.5), 0, Math.sin(ang) * (radius - 0.5));
                p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, tip, 1, 0, 0, 0, 0);
                if (t == 3) strike(p, radius, dmg, empowered);
                if (++t >= 8) cancel();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void strike(Player p, double radius, double dmg, boolean empowered) {
        Location origin = p.getLocation();
        int hits = 0;
        abilityDamage = true;
        try {
            for (Entity en : p.getNearbyEntities(radius, 2.5, radius)) {
                if (!(en instanceof LivingEntity le) || !hittable(le, p)) continue;
                if (le.getLocation().distanceSquared(origin) > radius * radius) continue;
                le.damage(dmg, p);
                Vector away = le.getLocation().toVector().subtract(origin.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                le.setVelocity(away.normalize().multiply(0.9).setY(0.35));
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                if (empowered) le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                hits++;
            }
        } finally {
            abilityDamage = false;
        }
        if (empowered && hits > 0) heal(p, getConfig().getDouble("sweep.empowered-heal", 8.0));
        if (hits > 0) p.getWorld().playSound(origin, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.7f);
    }

    /** Shift + right click: a soul chain yanks the target in front of you towards you. */
    private void grip(Player p) {
        int now = Bukkit.getCurrentTick();
        if (now < gripReady.getOrDefault(p.getUniqueId(), 0)) return;
        double range = getConfig().getDouble("grip.range", 14);

        RayTraceResult rr = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.9,
                en -> en instanceof LivingEntity le && hittable(le, p));
        if (rr == null || !(rr.getHitEntity() instanceof LivingEntity target)) {
            p.sendActionBar(Component.text("No target in range", NamedTextColor.GRAY));
            return;
        }
        gripReady.put(p.getUniqueId(), now + getConfig().getInt("grip.cooldown-seconds", 14) * 20);

        Location from = p.getEyeLocation().subtract(0, 0.35, 0);
        Location to = target.getLocation().add(0, 1, 0);
        int steps = (int) Math.max(4, from.distance(to) * 3);
        for (int i = 0; i <= steps; i++) {
            Location pt = from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) steps));
            p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, pt, 1, 0.03, 0.03, 0.03, 0);
            if (i % 2 == 0) p.getWorld().spawnParticle(Particle.SOUL, pt, 1, 0, 0, 0, 0.01);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
        p.getWorld().playSound(target.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);

        Vector pull = p.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        double dist = pull.length();
        if (dist > 1.2) {
            pull.normalize().multiply(Math.min(1.7, dist * 0.35 + 0.5)).setY(0.3);
            target.setVelocity(pull);
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1));
        double dmg = getConfig().getDouble("grip.damage", 3.0);
        if (dmg > 0) {
            abilityDamage = true;
            try {
                target.damage(dmg, p);
            } finally {
                abilityDamage = false;
            }
        }
    }

    // --------------------------------------------------------------------- HUD

    private void hud() {
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holding(p)) continue;
            Souls s = soulsOf(p);
            int max = maxSouls();
            StringBuilder pips = new StringBuilder();
            for (int i = 0; i < max; i++) pips.append(i < s.count ? "\u25CF" : "\u25CB");
            boolean full = s.count >= max;
            int sw = Math.max(0, sweepReady.getOrDefault(p.getUniqueId(), 0) - now);
            int gr = Math.max(0, gripReady.getOrDefault(p.getUniqueId(), 0) - now);
            Component c = Component.text("\u2620 " + pips, full ? NamedTextColor.RED : NamedTextColor.AQUA,
                            full ? new TextDecoration[]{TextDecoration.BOLD} : new TextDecoration[0])
                    .append(Component.text(full ? " EMPOWERED" : "", NamedTextColor.RED, TextDecoration.BOLD))
                    .append(Component.text("   Sweep ", NamedTextColor.GRAY))
                    .append(sw == 0 ? Component.text("READY", NamedTextColor.GREEN) : Component.text((sw + 19) / 20 + "s", NamedTextColor.YELLOW))
                    .append(Component.text("   Grip ", NamedTextColor.GRAY))
                    .append(gr == 0 ? Component.text("READY", NamedTextColor.GREEN) : Component.text((gr + 19) / 20 + "s", NamedTextColor.YELLOW));
            p.sendActionBar(c);
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("/" + label + " give [player]", NamedTextColor.YELLOW));
            return true;
        }
        Player target = args.length >= 2 ? getServer().getPlayerExact(args[1])
                : (sender instanceof Player self ? self : null);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(scythe()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + target.getName() + " the Soul Reaper.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
