package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LogLineParserTest {

  @Test
  void splitsTimestampFromMessage() {
    var parser = new LogLineParser();
    var line = parser.accept("2026-08-28T10:15:30.123456789Z [INFO] Done (2.1s)!").orElseThrow();

    assertEquals(Instant.parse("2026-08-28T10:15:30.123456789Z"), line.timestamp());
    assertEquals("[INFO] Done (2.1s)!", line.message());
  }

  @Test
  void dropsLinesAlreadySeenAfterReconnect() {
    // sinceTime has second resolution, so a reconnect replays the last second.
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.500000000Z first");

    assertTrue(parser.accept("2026-08-28T10:15:30.100000000Z earlier").isEmpty());
    assertTrue(parser.accept("2026-08-28T10:15:30.500000000Z same").isEmpty());
    assertTrue(parser.accept("2026-08-28T10:15:31.000000000Z later").isPresent());
  }

  @Test
  void passesThroughLinesWithoutParsableTimestamp() {
    var parser = new LogLineParser();
    assertEquals("no timestamp here", parser.accept("no timestamp here").orElseThrow().message());
  }

  @Test
  void passesThroughWhenThePrefixIsNotATimestamp() {
    var parser = new LogLineParser();
    assertEquals("[INFO] hello world",
      parser.accept("[INFO] hello world").orElseThrow().message());
  }

  @Test
  void remembersLastSeenForTheReconnectQuery() {
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.000000000Z hello");

    assertEquals(Instant.parse("2026-08-28T10:15:30Z"), parser.lastSeen().orElseThrow());
  }

  @Test
  void lastSeenIsEmptyBeforeAnythingArrives() {
    assertTrue(new LogLineParser().lastSeen().isEmpty());
  }

  @Test
  void preservesEmptyMessagesAfterTheTimestamp() {
    var parser = new LogLineParser();
    assertEquals("", parser.accept("2026-08-28T10:15:30.000000000Z ").orElseThrow().message());
  }
}
