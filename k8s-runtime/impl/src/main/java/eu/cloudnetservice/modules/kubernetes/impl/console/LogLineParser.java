package eu.cloudnetservice.modules.kubernetes.impl.console;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Splits the RFC3339 prefix Kubernetes prepends when logs are requested with
 * timestamps, and drops lines already seen.
 *
 * <p>The deduplication matters because {@code sinceTime} only has second resolution:
 * every reconnect replays the whole second the stream broke in.
 */
public final class LogLineParser {

  public record ParsedLine(Instant timestamp, String message) {
  }

  private volatile Instant lastSeen;

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

    if (this.lastSeen != null && !timestamp.isAfter(this.lastSeen)) {
      return Optional.empty();
    }

    this.lastSeen = timestamp;
    return Optional.of(new ParsedLine(timestamp, rawLine.substring(separator + 1)));
  }

  public Optional<Instant> lastSeen() {
    return Optional.ofNullable(this.lastSeen);
  }
}
