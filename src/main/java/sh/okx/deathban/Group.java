package sh.okx.deathban;

import lombok.Data;
import org.bukkit.configuration.ConfigurationSection;
import sh.okx.timeapi.TimeAPI;

import java.util.ArrayList;
import java.util.List;

@Data
public class Group implements Comparable<Group> {

  /**
   * Sentinel value for a permanent (never-expiring) ban.
   * Stored as a far-future epoch timestamp to remain compatible with the existing Timestamp
   * columns without overflow (Long.MAX_VALUE / 2 ≈ 146 million years from epoch).
   */
  public static final long PERMANENT = Long.MAX_VALUE / 2;

  private final String permission;
  private final int lives;
  private final int priority;
  private final List<String> commands;
  private final List<Long> times = new ArrayList<>();
  private long time = -1;

  public static Group deserialize(ConfigurationSection section) {
    Group group = new Group(
        section.getString("permission"),
        section.getInt("lives"),
        section.getInt("priority"),
        section.getStringList("commands"));
    if (section.isList("time")) {
      for (String t : section.getStringList("time")) {
        group.times.add(parseTime(t));
      }
    } else {
      group.time = parseTime(section.getString("time"));
    }
    return group;
  }

  /** Parses a time string into milliseconds, treating {@code "permanent"} as {@link #PERMANENT}. */
  private static long parseTime(String timeStr) {
    if (timeStr == null) return 0;
    if (timeStr.equalsIgnoreCase("permanent")) return PERMANENT;
    return new TimeAPI(timeStr).getMilliseconds();
  }

  @Override
  public int compareTo(Group group) {
    // high priority should be first
    return Integer.compare(group.getPriority(), priority);
  }

  /**
   * Returns the ban duration in milliseconds for the given number of prior bans.
   * If only a single time is configured, it is always returned.
   * If a list is configured, each entry corresponds to the nth ban; once the list is exhausted
   * the ban becomes permanent.
   */
  public long getTime(int bans) {
    if (time != -1) {
      return time;
    } else {
      return bans >= times.size() ? PERMANENT : times.get(bans);
    }
  }
}
