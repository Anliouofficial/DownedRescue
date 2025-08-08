package exe.example.downedRescue;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.*;

public class RescuePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, DownedPlayer> downedPlayers = new HashMap<>();
    private final Map<UUID, RescueSession> rescueSessions = new HashMap<>();
    private final Map<UUID, UUID> carryingMap = new HashMap<>(); // 救援者UUID -> 被抬者UUID
    private final Map<UUID, Float> originalWalkSpeeds = new HashMap<>();
    private final Map<UUID, Float> originalFlySpeeds = new HashMap<>();
    private int maxWaitTime;
    private double reviveHealth;
    private boolean preventJump;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CombatListener(), this);
        getServer().getPluginManager().registerEvents(new MovementListener(), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(), this);
        getServer().getPluginManager().registerEvents(new ActionRestrictionListener(), this);

        // 每秒更新任务
        new BukkitRunnable() {
            @Override
            public void run() {
                new ArrayList<>(downedPlayers.entrySet()).forEach(entry -> {
                    DownedPlayer dp = entry.getValue();
                    Player p = dp.getPlayer();

                    dp.update();
                    checkAutoRevive(p);

                    if (dp.isExpired()) {
                        p.setHealth(0);
                        dp.remove();
                        downedPlayers.remove(entry.getKey());
                        restorePlayerSpeed(p);
                    }
                });
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void loadConfig() {
        maxWaitTime = getConfig().getInt("max-wait-time", 45);
        reviveHealth = getConfig().getDouble("revive-health", 0.2);
        preventJump = getConfig().getBoolean("prevent-jump", true);
    }

    private void checkAutoRevive(Player p) {
        AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null && p.getHealth() >= maxHealthAttr.getValue()) {
            revivePlayer(p, true);
        }
    }

    private void restorePlayerSpeed(Player player) {
        // 恢复行走速度
        Float originalWalk = originalWalkSpeeds.remove(player.getUniqueId());
        if (originalWalk != null) {
            player.setWalkSpeed(originalWalk);
        } else {
            player.setWalkSpeed(0.2f);
        }

        // 恢复飞行速度
        Float originalFly = originalFlySpeeds.remove(player.getUniqueId());
        if (originalFly != null) {
            player.setFlySpeed(originalFly);
        } else {
            player.setFlySpeed(0.1f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player)) return;
        Player target = (Player) e.getTarget();
        if (downedPlayers.containsKey(target.getUniqueId())) {
            e.setCancelled(true);
            clearExistingTarget(target);
        }
    }

    private void clearExistingTarget(Player target) {
        target.getWorld().getNearbyEntities(target.getLocation(), 24, 24, 24).forEach(entity -> {
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                if (mob.getTarget() != null && mob.getTarget().getUniqueId().equals(target.getUniqueId())) {
                    mob.setTarget(null);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        DownedPlayer dp = downedPlayers.remove(p.getUniqueId());
        if (dp != null) {
            dp.remove();
            restorePlayerSpeed(p);
        }

        // 处理抬人状态退出
        if (carryingMap.containsKey(p.getUniqueId())) {
            releaseCarriedPlayer(p);
        }
        // 处理被抬玩家退出
        if (carryingMap.containsValue(p.getUniqueId())) {
            // 找到抬人者并清理
            UUID rescuerId = carryingMap.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(p.getUniqueId()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (rescuerId != null) {
                carryingMap.remove(rescuerId);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        DownedPlayer dp = downedPlayers.get(p.getUniqueId());
        if (dp != null) {
            dp.updateHologramPosition();
        }
    }

    // 释放被抬玩家方法
    private void releaseCarriedPlayer(Player rescuer) {
        UUID carriedUUID = carryingMap.remove(rescuer.getUniqueId());
        if (carriedUUID == null) return;

        Player carried = Bukkit.getPlayer(carriedUUID);

        if (carried != null) {
            // 确保玩家下马
            if (carried.isInsideVehicle() && carried.getVehicle() instanceof Player) {
                Player vehicle = (Player) carried.getVehicle();
                if (vehicle.getUniqueId().equals(rescuer.getUniqueId())) {
                    carried.leaveVehicle();
                }
            }

            carried.teleport(rescuer.getLocation().add(0, 0.5, 0));
            carried.sendActionBar("§e你已被放下");
            DownedPlayer dp = downedPlayers.get(carried.getUniqueId());
            if (dp != null) {
                dp.setCarried(false);
            }
            // 恢复被抬玩家速度
            restorePlayerSpeed(carried);
        }
        // 恢复抬起者速度
        restorePlayerSpeed(rescuer);
        rescuer.sendActionBar("§a你放下了玩家");
    }

    private class CombatListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageEvent e) {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();

            if (downedPlayers.containsKey(p.getUniqueId())) {
                e.setCancelled(true);
                return;
            }

            boolean isFatal = p.getHealth() - e.getFinalDamage() <= 0;

            if (isFatal) {
                boolean usedTotem = checkHand(p, p.getInventory().getItemInOffHand(), true) ||
                        checkHand(p, p.getInventory().getItemInMainHand(), false);

                if (usedTotem) {
                    triggerTotemEffect(p);
                }

                enterDownedState(p);
                e.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onAttack(EntityDamageByEntityEvent e) {
            handleDownedPlayerAttack(e);
            handleAttackOnDownedPlayer(e);
        }

        private void handleDownedPlayerAttack(EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player) {
                Player attacker = (Player) e.getDamager();
                if (downedPlayers.containsKey(attacker.getUniqueId())) {
                    e.setCancelled(true);
                    attacker.sendActionBar("§c倒地时无法攻击！");
                }
            }
        }

        private void handleAttackOnDownedPlayer(EntityDamageByEntityEvent e) {
            if (!(e.getEntity() instanceof Player)) return;
            Player target = (Player) e.getEntity();
            if (!downedPlayers.containsKey(target.getUniqueId())) return;

            e.setCancelled(true);

            if (e.getDamager() instanceof Player) {
                Player attacker = (Player) e.getDamager();
                DownedPlayer dp = downedPlayers.get(target.getUniqueId());
                dp.deductTime(5);

                String message = String.format("§c-5秒 §7剩余时间: §e%d秒", dp.getRemainingTime());
                showFloatingText(target, message);
                attacker.sendActionBar(message);
            }
        }

        private void showFloatingText(Player target, String text) {
            ArmorStand as = target.getWorld().spawn(target.getLocation().add(0, 2.5, 0), ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setCustomNameVisible(true);
                stand.setCustomName(text);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.addScoreboardTag("rescue_time_display");
            });
            new BukkitRunnable() {
                @Override
                public void run() {
                    as.remove();
                }
            }.runTaskLater(RescuePlugin.this, 20L);
        }

        private boolean checkHand(Player p, ItemStack item, boolean isOffhand) {
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                consumeItem(p, item, isOffhand);
                return true;
            }
            return false;
        }

        private void triggerTotemEffect(Player p) {
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getEyeLocation(), 250, 0.5, 0.5, 0.5, 0.5);
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
            p.setHealth(1.0);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        }

        private void consumeItem(Player p, ItemStack item, boolean isOffhand) {
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                if (isOffhand) {
                    p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                } else {
                    p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                }
            }
        }
    }

    private class MovementListener implements Listener {
        private final Set<UUID> jumpCooldown = new HashSet<>();

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onMove(PlayerMoveEvent e) {
            Player p = e.getPlayer();
            handleDownedPlayerMovement(p, e);
            handleRescuerMovement(p);
            handleCarrierMovement(p, e);
        }

        // 处理被抬玩家的移动同步
        private void handleCarrierMovement(Player carrier, PlayerMoveEvent e) {
            if (carryingMap.containsKey(carrier.getUniqueId())) {
                UUID carriedUUID = carryingMap.get(carrier.getUniqueId());
                Player carried = Bukkit.getPlayer(carriedUUID);

                if (carried != null && carried.isOnline()) {
                    // 更新被抬玩家的位置到救援者头顶
                    Location newLoc = carrier.getLocation().clone().add(0, 1.2, 0);
                    if (!carried.getLocation().equals(newLoc)) {
                        carried.teleport(newLoc);
                    }
                }
            }
        }

        // 合并后的蹲下事件处理
        @EventHandler
        public void onPlayerSneak(PlayerToggleSneakEvent e) {
            Player p = e.getPlayer();

            // 被抬玩家按Shift下马
            if (carryingMap.containsValue(p.getUniqueId()) && e.isSneaking()) {
                // 找到抬人者
                UUID rescuerId = carryingMap.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(p.getUniqueId()))
                        .map(Map.Entry::getKey)
                        .findFirst().orElse(null);
                if (rescuerId != null) {
                    Player rescuer = Bukkit.getPlayer(rescuerId);
                    if (rescuer != null) {
                        releaseCarriedPlayer(rescuer);
                    }
                }
            }

            // 倒地玩家加速死亡
            if (downedPlayers.containsKey(p.getUniqueId())) {
                DownedPlayer dp = downedPlayers.get(p.getUniqueId());
                dp.setAcceleratingDeath(e.isSneaking());
            }
        }

        private void handleDownedPlayerMovement(Player p, PlayerMoveEvent e) {
            if (carryingMap.containsValue(p.getUniqueId())) {
                // 被抬玩家位置由救援者控制
                e.setCancelled(true);
                return;
            }

            DownedPlayer dp = downedPlayers.get(p.getUniqueId());
            if (dp == null) return;

            if (preventJump) {
                if (isAttemptingJump(p, e.getFrom(), e.getTo())) {
                    e.setCancelled(true);
                    Vector velocity = p.getVelocity();
                    p.setVelocity(new Vector(velocity.getX(), -0.1, velocity.getZ()));
                    p.sendActionBar("§c倒地状态禁止跳跃");

                    if (!jumpCooldown.contains(p.getUniqueId())) {
                        jumpCooldown.add(p.getUniqueId());
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                jumpCooldown.remove(p.getUniqueId());
                            }
                        }.runTaskLater(RescuePlugin.this, 10L);
                    }
                }
            }
        }

        private boolean isAttemptingJump(Player player, Location from, Location to) {
            double verticalVelocity = player.getVelocity().getY();
            double heightChange = to.getY() - from.getY();
            return verticalVelocity > 0.1 && heightChange > 0.1;
        }

        private void handleRescuerMovement(Player p) {
            RescueSession session = rescueSessions.get(p.getUniqueId());
            if (session != null) session.checkConditions();
        }
    }

    private class InteractionListener implements Listener {
        @EventHandler
        public void onInteract(PlayerInteractEntityEvent e) {
            if (!(e.getRightClicked() instanceof Player)) return;
            Player rescuer = e.getPlayer();
            Player target = (Player) e.getRightClicked();

            if (downedPlayers.containsKey(target.getUniqueId())) {
                if (downedPlayers.containsKey(rescuer.getUniqueId())) {
                    rescuer.sendActionBar("§c你也是倒地状态，无法救援或抬起！");
                    return;
                }

                if (rescuer.isSneaking()) {
                    handleCarryPlayer(rescuer, target);
                } else {
                    startRescueSession(rescuer, target);
                }
            }
        }

        // 处理玩家抬起功能（直接骑在救援者头上）
        private void handleCarryPlayer(Player rescuer, Player target) {
            if (carryingMap.containsValue(target.getUniqueId())) {
                rescuer.sendActionBar("§c该玩家已被他人抬起！");
                return;
            }

            if (carryingMap.containsKey(rescuer.getUniqueId())) {
                rescuer.sendActionBar("§c你已经在抬着一个玩家了！");
                return;
            }

            // 保存原始速度
            originalWalkSpeeds.put(rescuer.getUniqueId(), rescuer.getWalkSpeed());
            originalFlySpeeds.put(rescuer.getUniqueId(), rescuer.getFlySpeed());
            originalWalkSpeeds.put(target.getUniqueId(), target.getWalkSpeed());
            originalFlySpeeds.put(target.getUniqueId(), target.getFlySpeed());

            // 设置新速度
            rescuer.setWalkSpeed(0.1f);  // 降低抬起者移动速度
            rescuer.setFlySpeed(0.05f);
            target.setWalkSpeed(0f);     // 完全禁止被抬玩家移动
            target.setFlySpeed(0f);

            // 让被抬玩家骑在救援者头上
            Location ridePos = rescuer.getLocation().add(0, 1.2, 0);
            target.teleport(ridePos);
            target.addPassenger(rescuer); // 让被抬玩家骑在救援者头上

            carryingMap.put(rescuer.getUniqueId(), target.getUniqueId());
            DownedPlayer dp = downedPlayers.get(target.getUniqueId());
            if (dp != null) {
                dp.setCarried(true);
            }
            rescuer.sendActionBar("§a你抬起了 " + target.getName() + " §a(蹲下+右键放下)");
            target.sendActionBar("§e你被 " + rescuer.getName() + " §e抬起了 §7(按Shift下来)");
        }

        // 放下玩家功能
        @EventHandler
        public void onCarrierInteract(PlayerInteractEvent e) {
            Player rescuer = e.getPlayer();
            if (carryingMap.containsKey(rescuer.getUniqueId()) && rescuer.isSneaking() &&
                    (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                releaseCarriedPlayer(rescuer);
            }
        }

        private void startRescueSession(Player rescuer, Player target) {
            if (rescuer.getUniqueId().equals(target.getUniqueId())) return;
            if (rescueSessions.containsKey(rescuer.getUniqueId())) return;

            RescueSession session = new RescueSession(rescuer, target);
            if (session.isValid()) {
                rescueSessions.put(rescuer.getUniqueId(), session);
                session.start();
            }
        }
    }

    private class ActionRestrictionListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteract(PlayerInteractEvent e) {
            if (downedPlayers.containsKey(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar("§c倒地时无法交互物品");
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onBlockPlace(BlockPlaceEvent e) {
            if (downedPlayers.containsKey(e.getPlayer().getUniqueId())) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar("§c倒地时无法放置方块");
            }
        }
    }

    private void enterDownedState(Player p) {
        if (downedPlayers.containsKey(p.getUniqueId())) return;

        p.setHealth(1.0);
        downedPlayers.put(p.getUniqueId(), new DownedPlayer(p, maxWaitTime, this));
        p.sendTitle("§c§l你倒下了！", "§e生命回满自动复活 或等待救援", 10, 60, 10);
        clearExistingTarget(p);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_DROWN, 1.0f, 1.0f);

        // 保存原始速度
        originalWalkSpeeds.put(p.getUniqueId(), p.getWalkSpeed());
        originalFlySpeeds.put(p.getUniqueId(), p.getFlySpeed());

        // 限制移动能力
        p.setWalkSpeed(0.05f);
        p.setFlySpeed(0.05f);
    }

    private void revivePlayer(Player p, boolean naturalHeal) {
        DownedPlayer dp = downedPlayers.remove(p.getUniqueId());
        if (dp != null) {
            dp.remove();
            restorePlayerSpeed(p);

            if (!naturalHeal) {
                AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    p.setHealth(Math.min(maxHealthAttr.getValue() * reviveHealth, maxHealthAttr.getValue()));
                }
            }

            p.sendTitle("§a§l复活成功！",
                    naturalHeal ? "§e自然恢复满血" : "§e生命恢复至" + (int)(reviveHealth*100) + "%",
                    10, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    @Override
    public void onDisable() {
        new ArrayList<>(downedPlayers.values()).forEach(dp -> {
            Player p = dp.getPlayer();
            dp.remove();
            restorePlayerSpeed(p);
        });
        downedPlayers.clear();

        // 清理所有骑乘关系
        carryingMap.forEach((rescuerId, carriedId) -> {
            Player rescuer = Bukkit.getPlayer(rescuerId);
            if (rescuer != null) {
                restorePlayerSpeed(rescuer);
            }
            Player carried = Bukkit.getPlayer(carriedId);
            if (carried != null) {
                restorePlayerSpeed(carried);
                if (carried.isInsideVehicle()) {
                    carried.leaveVehicle();
                }
            }
        });
        carryingMap.clear();

        // 清理速度记录
        originalWalkSpeeds.clear();
        originalFlySpeeds.clear();
    }

    private class RescueSession {
        private final Player rescuer;
        private final Player target;
        private final BossBar bossBar;
        private BukkitTask task;
        private int progress;
        private static final int REQUIRED_PROGRESS = 5;

        RescueSession(Player rescuer, Player target) {
            this.rescuer = rescuer;
            this.target = target;
            this.bossBar = Bukkit.createBossBar("§a救援进度...", BarColor.GREEN, BarStyle.SOLID);
        }

        boolean isValid() {
            if (carryingMap.containsValue(target.getUniqueId())) {
                rescuer.sendActionBar("§c目标已被抬起，无法救援！");
                return false;
            }

            return rescuer != null && target != null &&
                    rescuer.isOnline() && target.isOnline() &&
                    rescuer.getLocation().distanceSquared(target.getLocation()) <= 9 &&
                    isFacingRescuer();
        }

        void start() {
            bossBar.addPlayer(rescuer);
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!checkConditions()) {
                        cancel();
                        return;
                    }

                    progress++;
                    bossBar.setProgress((double) progress / REQUIRED_PROGRESS);

                    if (progress >= REQUIRED_PROGRESS) {
                        completeRescue();
                        cancel();
                    }
                }
            }.runTaskTimer(RescuePlugin.this, 0L, 20L);
        }

        boolean checkConditions() {
            if (!isValid()) {
                rescuer.sendActionBar("§c救援中断！");
                bossBar.removeAll();
                rescueSessions.remove(rescuer.getUniqueId());
                return false;
            }
            return true;
        }

        void completeRescue() {
            revivePlayer(target, false);
            rescuer.sendMessage("§a成功救援 " + target.getName());
            bossBar.removeAll();
            rescueSessions.remove(rescuer.getUniqueId());
        }

        boolean isFacingRescuer() {
            Vector rescuerDirection = rescuer.getLocation().getDirection();
            Vector toTarget = target.getLocation().toVector().subtract(rescuer.getLocation().toVector()).normalize();
            return rescuerDirection.dot(toTarget) > 0.8;
        }

        void cancel() {
            if (task != null) task.cancel();
            bossBar.removeAll();
        }
    }
}