package sh.okx.deathban;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.okx.deathban.commands.DeathBanCommand;
import sh.okx.deathban.commands.LivesCommand;
import sh.okx.deathban.commands.ReviveCommand;
import sh.okx.deathban.database.Database;
import sh.okx.deathban.database.PlayerData;
import sh.okx.deathban.listeners.DeathListener;
import sh.okx.deathban.listeners.JoinListener;
import sh.okx.deathban.scheduler.SchedulerUtil;
import sh.okx.deathban.timeformat.TimeFormat;
import sh.okx.deathban.timeformat.TimeFormatFactory;
import sh.okx.deathban.update.JoinUpdateNotifier;
import sh.okx.deathban.update.UpdateNotifier;
import sh.okx.deathban.update.VersionChecker;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.sql.Timestamp;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class DeathBan extends JavaPlugin {

  /**
   * Bans whose timestamp is beyond this threshold are treated as permanent.
   * Matches {@link Group#PERMANENT} (Long.MAX_VALUE / 2), divided by 4 as a safe boundary.
   */
  private static final long PERMANENT_THRESHOLD = Long.MAX_VALUE / 4;

  private Database database;
  private Group defaultGroup;
  private Set<Group> groups;
  private TimeFormat timeFormat;

  public Database getSDatabase() {
    return database;
  }

  @Override
  public void onEnable() {
    UpdateNotifier notifier = new UpdateNotifier(new VersionChecker(this));

    saveDefaultConfig();
    // Write any keys that exist in the bundled config.yml but are absent from the on-disk file.
    // This ensures upgrading servers automatically receive new config options.
    getConfig().options().copyDefaults(true);
    saveConfig();
    init();

    getServer().getPluginManager().registerEvents(new DeathListener(this), this);
    getServer().getPluginManager().registerEvents(new JoinListener(this), this);
    getServer().getPluginManager().registerEvents(new JoinUpdateNotifier(notifier, () -> getConfig().getBoolean("notify-update", true), "deathban.notify"), this);

    getCommand("lives").setExecutor(new LivesCommand(this));
    getCommand("revive").setExecutor(new ReviveCommand(this));
    getCommand("deathban").setExecutor(new DeathBanCommand(this));

    Metrics metrics = new Metrics(this, 30324);
    metrics.addCustomChart(new SimplePie("time_format",
        () -> getConfig().getString("time-format").split(" ")[0]));

    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      new DeathBanExpansion(this).register();
    }
  }

  public void reload() {
    closeDatabase();
    reloadConfig();
    // Copy any missing defaults to the file after reload as well
    getConfig().options().copyDefaults(true);
    saveConfig();
    init();
  }

  private void init() {
    String timeFormatStr = getConfig().getString("time-format", "date-format long long");
    timeFormat = TimeFormatFactory.get(timeFormatStr);
    database = new Database(this);
    defaultGroup = Group.deserialize(Objects.requireNonNull(getConfig().getConfigurationSection("default"), "The default group must exist"));

    groups = new TreeSet<>();
    ConfigurationSection sections = getConfig().getConfigurationSection("groups");
    if (sections != null) {
      for (String key : sections.getKeys(false)) {
        ConfigurationSection section = Objects.requireNonNull(sections.getConfigurationSection(key));
        groups.add(Group.deserialize(section));
      }
    }
  }

  @Override
  public void onDisable() {
    closeDatabase();
  }

  private void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  /** Returns true if the given ban timestamp represents a permanent (never-expiring) ban. */
  public static boolean isPermanentBan(Timestamp ban) {
    return ban != null && ban.getTime() > PERMANENT_THRESHOLD;
  }

  public boolean checkBan(PlayerData data) {
    Player player = Bukkit.getPlayer(data.getUuid());
    Group group = getGroup(player);
    if (data.getDeaths() < group.getLives()) {
      return false;
    }

    SchedulerUtil.runForPlayer(this, player, () -> ban(player, group.getTime(data.getBans())));
    return true;
  }

  @SuppressWarnings("deprecation")
  public void ban(Player player, long time) {
    Group group = getGroup(player);
    PlayerData data = database.getData(player.getUniqueId());

    data.setDeaths(0);
    boolean permanent = (time == Group.PERMANENT);
    Timestamp ban = new Timestamp(permanent ? Long.MAX_VALUE / 2 : System.currentTimeMillis() + time);
    data.setBan(ban);
    data.setBans(data.getBans() + 1);
    database.save(data);

    // Run any configured group commands (e.g. integration with external ban plugins)
    for (String command : group.getCommands()) {
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replaceStats(command, player));
    }

    // Optionally add to the server's built-in ban list
    if (getConfig().getBoolean("use-vanilla-bans", false)) {
      addVanillaBan(player.getName(), permanent, ban);
    }

    // Kick with the appropriate message
    String kickMsg = permanent
        ? replaceStats(getMessage("kick-permanent"), player)
        : replaceStats(getDateMessage(ban, "kick"), player);
    player.kickPlayer(kickMsg);

    // Broadcast to the server if enabled and the message is non-empty
    if (getConfig().getBoolean("announce-ban", true)) {
      String announceMsg = permanent
          ? replaceStats(getMessage("announce-permanent"), player)
          : replaceStats(getDateMessage(ban, "announce"), player);
      if (!announceMsg.isEmpty()) {
        Bukkit.broadcastMessage(announceMsg);
      }
    }
  }

  /** Adds the player to the server's vanilla ban list. Expiry is null for permanent bans. */
  @SuppressWarnings("deprecation")
  public void addVanillaBan(String playerName, boolean permanent, Timestamp expiry) {
    String reason = getMessage(permanent ? "kick-permanent" : "kick");
    Date expiryDate = permanent ? null : new Date(expiry.getTime());
    Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, expiryDate, "DeathBan");
  }

  /** Removes the player from the server's vanilla ban list if use-vanilla-bans is enabled. */
  @SuppressWarnings("deprecation")
  public void removeVanillaBan(String playerName) {
    if (getConfig().getBoolean("use-vanilla-bans", false)) {
      Bukkit.getBanList(BanList.Type.NAME).pardon(playerName);
    }
  }

  public String getDateMessage(Date date, String type) {
    return getMessage(type)
        .replace("%time%", timeFormat.format(date));
  }

  public String replaceStats(String string, Player player) {
    PlayerData data = database.getData(player.getUniqueId());
    Group group = getGroup(player);
    return ChatColor.translateAlternateColorCodes('&', string)
        .replace("%player%", player.getName())
        .replace("%lives%", group.getLives() - data.getDeaths() + "")
        .replace("%maxlives%", group.getLives() + "")
        .replace("%deaths%", data.getDeaths() + "")
        .replace("%bans%", data.getBans() + "");
  }

  public String getMessage(String path) {
    String message = getConfig().getString("messages." + path);
    if (message == null) {
      getLogger().warning("Missing config key 'messages." + path
          + "' — run '/deathban reload' or delete config.yml to regenerate defaults.");
      return "";
    }
    return ChatColor.translateAlternateColorCodes('&', message);
  }

  public Group getGroup(OfflinePlayer player) {
    if (!(player instanceof Player) || !player.isOnline()) {
      return defaultGroup;
    }
    Player p = (Player) player;
    for (Group group : groups) {
      if (p.hasPermission(group.getPermission())) {
        return group;
      }
    }
    return defaultGroup;
  }
}
