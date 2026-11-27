package eu.cloudnetservice.modules.kubernetes.impl.console;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Splits the RFC3339 prefix Kubernetes prepends when logs are requested with
 * timestamps, and drops lines replayed after a reconnect.
 *
 * <p>The deduplication matters because {@code sinceTime} only has second resolution:
 * every reconnect replays the whole second the stream broke in. Several distinct lines
 * (a stack trace, for example) can share one timestamp, so replayed lines are skipped by
 * count rather than by timestamp: after {@link #beginReplay()} exactly as many lines at
 * the last seen timestamp are skipped as were emitted for it so far.
 */
public final class LogLineParser {

  public record ParsedLine(Instant timestamp, String message) {
  }

  private volatile Instant lastSeen;
  // lines emitted at lastSeen, across all connections
  private int emittedAtLastSeen;
  // lines at lastSeen still to skip on the current connection
  private int replaySkips;

  /**
   * Marks the start of a new connection. Lines at the last seen timestamp that the
   * server replays are skipped, as many as were already emitted for that timestamp.
   */
  public void beginReplay() {
    this.replaySkips = this.emittedAtLastSeen;
  }

  public Optional<ParsedLine> accept(String rawLine) {
    var separator = rawLine.indexOf(' ');
    if (separator < 0) {
      return Optional.of(new ParsedLine(null, rawLine));
    }

    Instant timestamp;
    try {
      timestamp = Instant.parse(rawLine.substring(0, separator));
    } catch (DateTimeParseException exception) {
      return Optional.of(new ParsedLine(null, rawLine));
    }

    var message = rawLine.substring(separator + 1);
    if (this.lastSeen == null || timestamp.isAfter(this.lastSeen)) {
      this.lastSeen = timestamp;
      this.emittedAtLastSeen = 1;
      this.replaySkips = 0;
      return Optional.of(new ParsedLine(timestamp, message));
    }

    if (timestamp.equals(this.lastSeen)) {
      if (this.replaySkips > 0) {
        this.replaySkips--;
        return Optional.empty();
      }
      this.emittedAtLastSeen++;
      return Optional.of(new ParsedLine(timestamp, message));
    }

    // older than the last seen line: history replayed by sinceTime
    return Optional.empty();
  }

  public Optional<Instant> lastSeen() {
    return Optional.ofNullable(this.lastSeen);
  }
}
