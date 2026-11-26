package eu.cloudnetservice.modules.kubernetes.impl.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ResourceNamingTest {

  private static final Pattern DNS_1123 = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
  private static final Pattern LABEL_VALUE = Pattern.compile("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");
  private static final UUID ID = UUID.fromString("a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74");

  @Test
  void podNameCombinesServiceNameAndShortId() {
    assertEquals("cn-lobby-1-a3f9c2e1", ResourceNaming.podName("Lobby-1", ID));
  }

  @Test
  void podNameLowercasesAndStaysDns1123() {
    var name = ResourceNaming.podName("MixedCase-9", ID);
    assertTrue(DNS_1123.matcher(name).matches(), name);
  }

  @Test
  void podNameTruncatesOverlongInputToSixtyThreeChars() {
    var name = ResourceNaming.podName("a".repeat(120), ID);
    assertTrue(name.length() <= 63, "was " + name.length());
    assertTrue(DNS_1123.matcher(name).matches(), name);
  }

  @Test
  void pvcNameIgnoresUniqueIdSoItIsStableAcrossRestarts() {
    // Critical: if the uniqueId leaked in here, the server would get a fresh empty
    // volume on every start.
    assertEquals("cn-survival-1-data", ResourceNaming.pvcName("Survival-1"));
    assertEquals(ResourceNaming.pvcName("Survival-1"), ResourceNaming.pvcName("survival-1"));
  }

  @Test
  void secretNameDerivesFromPodName() {
    assertEquals("cn-lobby-1-a3f9c2e1-wrapper", ResourceNaming.secretName("cn-lobby-1-a3f9c2e1"));
  }

  @Test
  void podFqdnFollowsHostnameSubdomainScheme() {
    assertEquals(
      "cn-lobby-1-a3f9c2e1.cloudnet-services.cloudnet.svc.cluster.local",
      ResourceNaming.podFqdn("cn-lobby-1-a3f9c2e1", "cloudnet-services", "cloudnet", "cluster.local"));
  }

  @Test
  void sanitizeCollapsesIllegalCharacters() {
    var name = ResourceNaming.podName("Lobby_#1 Test", ID);
    assertTrue(DNS_1123.matcher(name).matches(), name);
  }

  @Test
  void labelValueKeepsAPlainTaskNameUnchanged() {
    assertEquals("Lobby", ResourceNaming.labelValue("Lobby"));
  }

  @Test
  void labelValueReplacesCharactersOutsideTheAllowedSetWithHyphens() {
    assertEquals("Lobby--1", ResourceNaming.labelValue("Lobby #1"));
  }

  @Test
  void labelValueTrimsNonAlphanumericEdges() {
    assertEquals("lobby", ResourceNaming.labelValue("_lobby."));
  }

  @Test
  void labelValueIsClampedToSixtyThreeCharsAndEndsAlphanumeric() {
    var value = ResourceNaming.labelValue("a".repeat(100) + ".");

    assertTrue(value.length() <= 63, "was " + value.length());
    assertTrue(LABEL_VALUE.matcher(value).matches(), value);
  }

  @Test
  void labelValuesOfDistinctLongTaskNamesStayDistinct() {
    var shared = "a".repeat(80);

    assertNotEquals(ResourceNaming.labelValue(shared + "one"), ResourceNaming.labelValue(shared + "two"),
      "truncation alone would merge these two tasks onto one label");
  }

  @Test
  void labelValueFallsBackToAValidValueWhenNothingSurvivesSanitising() {
    var value = ResourceNaming.labelValue("#!#");

    assertTrue(LABEL_VALUE.matcher(value).matches(), value);
  }

  @Test
  void stagingKeyIsDerivedFromThePodName() {
    assertEquals("services/cn-lobby-1-a3f9c2e1.tar", ResourceNaming.stagingKey("cn-lobby-1-a3f9c2e1"));
  }
}
