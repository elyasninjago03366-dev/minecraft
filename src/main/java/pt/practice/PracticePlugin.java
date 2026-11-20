package pt.practice;

import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PracticePlugin extends JavaPlugin implements Listener {

    static class Session {
        UUID owner;
        String mode;
        Location origin, start, npcLoc;
        Zombie npc;
        List<Block> structure = new ArrayList<Block>();
        List<Block> placed = new ArrayList<Block>();
        boolean attacking;
        int hitsLeft;
        long nextHit;
        double baseY;
    }

    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();
    private final Map<UUID, Session> bots = new HashMap<UUID, Session>();
    private final Set<UUID> dummyIds = new HashSet<UUID>();
    private final List<Zombie> dummies = new ArrayList<Zombie>();
    private long tick = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                tick += 2;
                tickAll();
            }
        }, 2L, 2L);
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<Session>(sessions.values())) cleanup(s);
        sessions.clear();
        removeDummies();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage("§e/pt bridge | dummy [remove] | clutch | kbreset | leave | clear | kb <h> <v> | set <dummy|clutch|kbreset> <h|v|hits|interval> <مقدار> | info");
            return true;
        }
        String sub = a[0].toLowerCase();

        if (sub.equals("info")) {
            for (String m : new String[]{"dummy", "clutch", "kbreset"}) {
                sender.sendMessage("§6" + m + "§f: h=" + getConfig().getDouble(m + ".h") + " v=" + getConfig().getDouble(m + ".v")
                        + (m.equals("dummy") ? "" : " hits=" + getConfig().getInt(m + ".hits") + " interval=" + getConfig().getInt(m + ".interval")));
            }
            return true;
        }

        if (sub.equals("kb")) {
            if (a.length < 3) { sender.sendMessage("§c/pt kb <h> <v>"); return true; }
            return setValue(sender, "dummy", "h", a[1]) & setValue(sender, "dummy", "v", a[2]);
        }

        if (sub.equals("set")) {
            if (a.length < 4) { sender.sendMessage("§c/pt set <dummy|clutch|kbreset> <h|v|hits|interval> <مقدار>"); return true; }
            return setValue(sender, a[1].toLowerCase(), a[2].toLowerCase(), a[3]);
        }

        if (!(sender instanceof Player)) { sender.sendMessage("Only players."); return true; }
        Player p = (Player) sender;

        if (sub.equals("leave")) { leave(p); p.sendMessage("§aخارج شدی."); return true; }

        if (sub.equals("clear")) {
            Session s = sessions.get(p.getUniqueId());
            if (s != null) { resetSession(s, p); p.sendMessage("§aریست شد."); }
            return true;
        }

        if (sub.equals("bridge")) { startBridge(p); return true; }
        if (sub.equals("clutch")) { startBot(p, "clutch"); return true; }
        if (sub.equals("kbreset")) { startBot(p, "kbreset"); return true; }

        if (sub.equals("dummy")) {
            if (a.length > 1 && a[1].equalsIgnoreCase("remove")) { removeDummies(); p.sendMessage("§aNPCها پاک شدن."); return true; }
            spawnDummies(p);
            return true;
        }
        sender.sendMessage("§cدستور نامعتبر.");
        return true;
    }

    private boolean setValue(CommandSender sender, String mode, String key, String val) {
        if (!(mode.equals("dummy") || mode.equals("clutch") || mode.equals("kbreset"))) { sender.sendMessage("§cmode نامعتبر"); return false; }
        boolean isInt = key.equals("hits") || key.equals("interval");
        if (!(key.equals("h") || key.equals("v") || isInt) || (mode.equals("dummy") && isInt)) { sender.sendMessage("§ckey نامعتبر"); return false; }
        try {
            if (isInt) getConfig().set(mode + "." + key, Integer.parseInt(val));
            else getConfig().set(mode + "." + key, Double.parseDouble(val));
        } catch (NumberFormatException e) { sender.sendMessage("§cعدد نامعتبر"); return false; }
        saveConfig();
        sender.sendMessage("§a" + mode + "." + key + " = " + val);
        return true;
    }

    // ---------------------------------------------------------------- sessions

    private void giveItems(Player p, boolean stick) {
        if (stick) p.getInventory().addItem(new ItemStack(Material.STICK, 1));
        p.getInventory().addItem(new ItemStack(Material.SANDSTONE, 64), new ItemStack(Material.SANDSTONE, 64));
    }

    private void startBridge(Player p) {
        leave(p);
        Session s = new Session();
        s.owner = p.getUniqueId();
        s.mode = "bridge";
        s.origin = p.getLocation().clone();
        s.start = p.getLocation().clone();
        s.baseY = p.getLocation().getY();
        sessions.put(p.getUniqueId(), s);
        giveItems(p, false);
        p.sendMessage("§aBridge شروع شد. اگه بیفتی برمیگردی به نقطه شروع. /pt clear = پاک کردن بلاک‌ها، /pt leave = خروج");
    }

    private void startBot(Player p, String mode) {
        leave(p);
        Session s = new Session();
        s.owner = p.getUniqueId();
        s.mode = mode;
        s.origin = p.getLocation().clone();
        World w = p.getWorld();
        int y = Math.min(p.getLocation().getBlockY() + 25, 200);
        int bx = p.getLocation().getBlockX(), bz = p.getLocation().getBlockZ();
        s.baseY = y;

        Vector d = p.getLocation().getDirection();
        int dx = 0, dz = 0;
        if (Math.abs(d.getX()) > Math.abs(d.getZ())) dx = d.getX() > 0 ? 1 : -1;
        else dz = d.getZ() > 0 ? 1 : -1;

        if (mode.equals("clutch")) {
            for (int i = 0; i <= 12; i++) addStruct(s, w.getBlockAt(bx + dx * i, y, bz + dz * i));
            s.start = center(w, bx + dx * 2, y + 1, bz + dz * 2, dx, dz);
            s.npcLoc = center(w, bx + dx * 9, y + 1, bz + dz * 9, -dx, -dz);
        } else {
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) addStruct(s, w.getBlockAt(bx + x, y, bz + z));
            s.start = center(w, bx, y + 1, bz + 1, 0, -1);
            s.npcLoc = center(w, bx, y + 1, bz - 1, 0, 1);
        }

        s.npc = spawnNpc(s.npcLoc, true, mode.equals("clutch") ? "§cClutch Bot" : "§cKB Reset Bot");
        bots.put(s.npc.getUniqueId(), s);
        sessions.put(p.getUniqueId(), s);

        p.teleport(s.start);
        p.setVelocity(new Vector(0, 0, 0));
        giveItems(p, true);
        p.sendMessage("§aبه NPC ضربه بزن تا شروع کنه به زدنت. /pt leave = خروج");
    }

    private void addStruct(Session s, Block b) {
        b.setType(Material.QUARTZ_BLOCK);
        s.structure.add(b);
    }

    private Location center(World w, int x, int y, int z, int fx, int fz) {
        Location l = new Location(w, x + 0.5, y, z + 0.5);
        l.setDirection(new Vector(fx, 0, fz));
        return l;
    }

    private void leave(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        cleanup(s);
        p.teleport(s.origin);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0);
    }

    private void cleanup(Session s) {
        if (s.npc != null) {
            bots.remove(s.npc.getUniqueId());
            s.npc.remove();
        }
        for (Block b : s.structure) b.setType(Material.AIR);
        for (Block b : s.placed) b.setType(Material.AIR);
        s.structure.clear();
        s.placed.clear();
    }

    private void resetSession(Session s, Player p) {
        for (Block b : s.placed) b.setType(Material.AIR);
        s.placed.clear();
        s.attacking = false;
        p.teleport(s.start);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0);
        p.setHealth(p.getMaxHealth());
    }

    // ---------------------------------------------------------------- ticking

    private void tickAll() {
        for (Session s : new ArrayList<Session>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.owner);
            if (p == null || !p.isOnline()) continue;

            if (p.getLocation().getY() < s.baseY - (s.mode.equals("bridge") ? 12 : 10)) {
                resetSession(s, p);
                continue;
            }
            if (s.npc == null) continue;
            if (s.npc.isDead() || !s.npc.isValid()) continue;

            look(s.npc, s.npcLoc, p.getLocation());

            if (s.attacking && tick >= s.nextHit) hit(s, p);
        }
    }

    private void hit(Session s, Player p) {
        double h = getConfig().getDouble(s.mode + ".h", 0.5);
        double v = getConfig().getDouble(s.mode + ".v", 0.4);
        int interval = Math.max(1, getConfig().getInt(s.mode + ".interval", 10));

        Vector dir = p.getLocation().toVector().subtract(s.npcLoc.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(h);
        dir.setY(v);

        p.setHealth(p.getMaxHealth());
        p.damage(0.01, s.npc);
        p.setVelocity(dir);

        s.hitsLeft--;
        if (s.hitsLeft <= 0) s.attacking = false;
        else s.nextHit = tick + interval;
    }

    // ---------------------------------------------------------------- NPCs

    private Zombie spawnNpc(Location loc, boolean noAI, String name) {
        Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
        z.setBaby(false);
        z.setVillager(false);
        z.setCanPickupItems(false);
        z.setRemoveWhenFarAway(false);
        z.setCustomName(name);
        z.setCustomNameVisible(true);
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false), true);
        z.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 6, false), true);
        if (noAI) setNoAI(z);
        return z;
    }

    private String nmsVersion() {
        return Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
    }

    private void setNoAI(Entity e) {
        try {
            String v = nmsVersion();
            Object handle = e.getClass().getMethod("getHandle").invoke(e);
            Class<?> nbt = Class.forName("net.minecraft.server." + v + ".NBTTagCompound");
            Class<?> ent = Class.forName("net.minecraft.server." + v + ".Entity");
            Object tag = nbt.newInstance();
            ent.getMethod("c", nbt).invoke(handle, tag);
            nbt.getMethod("setInt", String.class, int.class).invoke(tag, "NoAI", 1);
            ent.getMethod("f", nbt).invoke(handle, tag);
        } catch (Throwable t) {
            getLogger().warning("NoAI failed: " + t);
        }
    }

    private void look(Zombie z, Location npcLoc, Location target) {
        double dx = target.getX() - npcLoc.getX();
        double dz = target.getZ() - npcLoc.getZ();
        double dy = target.getY() - npcLoc.getY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        Location l = npcLoc.clone();
        l.setYaw(yaw);
        l.setPitch(pitch);
        z.teleport(l);
        try {
            Object handle = z.getClass().getMethod("getHandle").invoke(z);
            Class<?> el = Class.forName("net.minecraft.server." + nmsVersion() + ".EntityLiving");
            setFloat(el, handle, "aK", yaw);
            setFloat(el, handle, "aI", yaw);
        } catch (Throwable ignored) {
        }
    }

    private void setFloat(Class<?> c, Object o, String name, float val) throws Exception {
        Field f = c.getField(name);
        f.setFloat(o, val);
    }

    private void spawnDummies(Player p) {
        Vector d = p.getLocation().getDirection().setY(0);
        if (d.lengthSquared() == 0) d = new Vector(0, 0, 1);
        d.normalize();
        Vector perp = new Vector(-d.getZ(), 0, d.getX());
        Location base = p.getLocation().add(d.clone().multiply(3));
        Location[] spots = new Location[]{base.clone().add(perp.clone().multiply(2)), base.clone().subtract(perp.clone().multiply(2))};
        int n = 1;
        for (Location l : spots) {
            Vector face = p.getLocation().toVector().subtract(l.toVector());
            l.setDirection(face);
            Zombie z = spawnNpc(l, false, "§bDummy " + n++);
            dummies.add(z);
            dummyIds.add(z.getUniqueId());
        }
        p.sendMessage("§a۲ NPC ساخته شد. /pt kb <h> <v> برای تنظیم ناک‌بک، /pt dummy remove برای پاک کردن");
    }

    private void removeDummies() {
        for (Zombie z : dummies) z.remove();
        dummies.clear();
        dummyIds.clear();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();

        if (en instanceof Player && sessions.containsKey(en.getUniqueId())
                && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            return;
        }

        UUID id = en.getUniqueId();
        Session bs = bots.get(id);
        boolean dummy = dummyIds.contains(id);
        if (bs == null && !dummy) return;

        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent)) return;
        Entity dm = ((EntityDamageByEntityEvent) e).getDamager();
        if (!(dm instanceof Player)) return;
        Player p = (Player) dm;

        if (bs != null) {
            if (p.getUniqueId().equals(bs.owner) && !bs.attacking) {
                bs.attacking = true;
                bs.hitsLeft = getConfig().getInt(bs.mode + ".hits", 3);
                bs.nextHit = tick + Math.max(1, getConfig().getInt(bs.mode + ".interval", 10));
            }
            return;
        }

        double h = getConfig().getDouble("dummy.h", 0.4);
        double v = getConfig().getDouble("dummy.v", 0.36);
        Vector dir = en.getLocation().toVector().subtract(p.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(h);
        dir.setY(v);
        en.setVelocity(dir);
        en.playEffect(EntityEffect.HURT);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (bots.containsKey(id) || dummyIds.contains(id)) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.placed.add(e.getBlock());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && s.structure.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leave(e.getPlayer());
    }
}
