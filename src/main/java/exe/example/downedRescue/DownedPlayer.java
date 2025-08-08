package exe.example.downedRescue;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

public class DownedPlayer {
    // 配置常量
    private static final double HOLOGRAM_Y_OFFSET = 2.2;
    private static final int PARTICLE_COUNT = 15;
    private static final double PARTICLE_X_OFFSET = 0.3;
    private static final double PARTICLE_Y_OFFSET = 0.5;
    private static final double PARTICLE_Z_OFFSET = 0.3;
    private static final long PARTICLE_TASK_INTERVAL = 8L;

    // 对象字段
    private final int maxTime;
    private final Player player;
    private final BossBar bossBar;
    private final BukkitTask particleTask;
    private final ArmorStand hologram;
    private final Particle.DustOptions dust;
    private int remainingTime;
    private boolean acceleratingDeath;
    private boolean carried; // 是否被抬起状态

    public ArmorStand getHologram() {
        return hologram;
    }

    public void setAcceleratingDeath(boolean accelerating) {
        this.acceleratingDeath = accelerating;
    }

    public void setCarried(boolean carried) {
        this.carried = carried;
    }

    public boolean isCarried() {
        return carried;
    }

    public DownedPlayer(Player player, int maxTime, JavaPlugin plugin) {
        this.maxTime = maxTime;
        this.player = player;
        this.remainingTime = maxTime;
        this.dust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.5f);
        this.acceleratingDeath = false;
        this.carried = false;

        // 初始化BossBar
        BossBarStyle bossBarStyle = loadBossBarStyle(plugin);
        this.bossBar = createBossBar(bossBarStyle);

        // 创建悬浮文字
        this.hologram = createHologram();

        // 启动粒子效果
        this.particleTask = startParticleTask(plugin);
    }

    // region Initialization Methods
    private BossBarStyle loadBossBarStyle(JavaPlugin plugin) {
        BarColor color;
        try {
            color = BarColor.valueOf(
                    plugin.getConfig().getString("bossbar.color", "RED")
            );
        } catch (IllegalArgumentException e) {
            color = BarColor.RED;
            plugin.getLogger().warning("使用了无效的BossBar颜色，已重置为默认RED");
        }

        BarStyle style;
        try {
            style = BarStyle.valueOf(
                    plugin.getConfig().getString("bossbar.style", "SEGMENTED_20")
            );
        } catch (IllegalArgumentException e) {
            style = BarStyle.SEGMENTED_20;
            plugin.getLogger().warning("使用了无效的BossBar样式，已重置为默认SEGMENTED_20");
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("bossbar.title", "§4§l救援倒计时")
        );

        return new BossBarStyle(title, color, style);
    }

    private BossBar createBossBar(BossBarStyle style) {
        BossBar bar = Bukkit.createBossBar(style.title, style.color, style.style);
        bar.addPlayer(player);
        bar.setVisible(true);
        return bar;
    }

    private ArmorStand createHologram() {
        return player.getWorld().spawn(
                player.getLocation().add(0, HOLOGRAM_Y_OFFSET, 0),
                ArmorStand.class,
                stand -> {
                    stand.setVisible(false);
                    stand.setCustomNameVisible(true);
                    stand.setCustomName("§c⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡ 倒地状态 ⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡⚡");
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.addScoreboardTag("rescue_hologram");
                }
        );
    }

    private BukkitTask startParticleTask(JavaPlugin plugin) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                spawnParticles();
            }
        }.runTaskTimer(plugin, 0L, PARTICLE_TASK_INTERVAL);
    }

    private void spawnParticles() {
        player.getWorld().spawnParticle(
                Particle.DUST,
                player.getLocation().add(0, 1, 0),
                PARTICLE_COUNT,
                PARTICLE_X_OFFSET,
                PARTICLE_Y_OFFSET,
                PARTICLE_Z_OFFSET,
                dust
        );
    }
    // endregion

    // region Core Logic
    public void update() {
        remainingTime = Math.max(0, remainingTime + (acceleratingDeath ? -10 : -1));
        updateBossBar();
        updateActionBar();
        updateHologramPosition();
    }

    private void updateBossBar() {
        bossBar.setProgress((double) remainingTime / maxTime);
        bossBar.setTitle(formatBossBarTitle());
    }

    private void updateActionBar() {
        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent("§c按住SHIFT加速死亡 §7| §e剩余: §6" + remainingTime + "秒")
        );
    }

    void updateHologramPosition() {
        hologram.teleport(player.getLocation().add(0, HOLOGRAM_Y_OFFSET, 0));
    }

    public void deductTime(int seconds) {
        remainingTime = Math.max(0, remainingTime - seconds);
    }
    // endregion

    // region Utility Methods
    private String formatBossBarTitle() {
        return String.format("§4§l剩余时间: §f%d秒", remainingTime);
    }

    public int getRemainingTime() {
        return remainingTime;
    }

    public void remove() {
        if (bossBar != null) bossBar.removeAll();
        if (particleTask != null) particleTask.cancel();
        if (hologram != null && hologram.isValid()) hologram.remove();
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isExpired() {
        return remainingTime <= 0;
    }
    // endregion

    // region Helper Class
    private static class BossBarStyle {
        final String title;
        final BarColor color;
        final BarStyle style;

        BossBarStyle(String title, BarColor color, BarStyle style) {
            this.title = title;
            this.color = color;
            this.style = style;
        }
    }
    // endregion
}