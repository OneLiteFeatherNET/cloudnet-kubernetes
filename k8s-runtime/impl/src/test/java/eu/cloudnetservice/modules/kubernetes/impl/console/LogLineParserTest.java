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
  void dropsLinesOlderThanLastSeenDuringReplay() {
    // sinceTime has second resolution, so a reconnect replays the last second.
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.500000000Z first");
    parser.beginReplay();

    assertTrue(parser.accept("2026-08-28T10:15:30.100000000Z earlier").isEmpty(),
      "a line older than the last seen one is replayed history and must be dropped");
  }

  @Test
  void emitsNewerLinesAfterReplay() {
    var parser = new LogLineParser();
    parser.accept("2026-08-28T10:15:30.500000000Z first");
    parser.beginReplay();

    assertTrue(parser.accept("2026-08-28T10:15:31.000000000Z later").isPresent(),
      "a line newer than the last seen one is new output and must be emitted");
  }

  @Test
  void emitsDistinctLinesSharingATimestamp() {
    // a stack trace is written line by line, all lines carrying the same timestamp
    var parser = new LogLineParser();
    var timestamp = "2026-08-28T10:15:30.500000000Z";

    assertEquals("first", parser.accept(timestamp + " first").orElseThrow().message(),
      "the first line of a timestamp must be emitted");
    assertEquals("second", parser.accept(timestamp + " second").orElseThrow().message(),
      "a second distinct line with the same timestamp must not be dropped");
    assertEquals("third", parser.accept(timestamp + " third").orElseThrow().message(),
      "a third distinct line with the same timestamp must not be dropped");
  }

  @Test
  void replaySkipsExactlyTheLinesAlreadyEmittedAtLastSeen() {
    var parser = new LogLineParser();
    var timestamp = "2026-08-28T10:15:30.500000000Z";
    parser.accept(timestamp + " first");
    parser.accept(timestamp + " second");

    parser.beginReplay();
    assertTrue(parser.accept(timestamp + " first").isEmpty(), "replayed first line must be skipped");
    assertTrue(parser.accept(timestamp + " second").isEmpty(), "replayed second line must be skipped");
    assertEquals("third", parser.accept(timestamp + " third").orElseThrow().message(),
      "a line sharing the last seen timestamp that was not emitted yet must be emitted after replay");
  }

  @Test
  void replaySkipsEveryLineEmittedAtLastSeenAcrossRepeatedReconnects() {
    var parser = new LogLineParser();
    var timestamp = "2026-08-28T10:15:30.500000000Z";
    parser.accept(timestamp + " first");

    parser.beginReplay();
    assertTrue(parser.accept(timestamp + " first").isEmpty(), "first reconnect must skip the first line");
    assertEquals("second", parser.accept(timestamp + " second").orElseThrow().message(),
      "a line first seen after a reconnect must be emitted");

    parser.beginReplay();
    assertTrue(parser.accept(timestamp + " first").isEmpty(), "second reconnect must skip the first line");
    assertTrue(parser.accept(timestamp + " second").isEmpty(), "second reconnect must skip the second line");
    assertEquals("third", parser.accept(timestamp + " third").orElseThrow().message(),
      "a line never emitted before must still be emitted on the second reconnect");
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
