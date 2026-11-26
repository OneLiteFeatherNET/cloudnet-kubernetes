package eu.cloudnetservice.modules.kubernetes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StagingConfigurationTest {

  private static final String NODE_ENDPOINT = "https://s3.external.example";
  private static final String POD_ENDPOINT = "http://rook-ceph-rgw-cloudnet-store.rook-ceph.svc:80";

  @Test
  void effectivePodEndpointIsThePodEndpointWhenOneIsSet() {
    var staging = StagingConfiguration.builder()
      .endpoint(NODE_ENDPOINT)
      .podEndpoint(POD_ENDPOINT)
      .build();

    assertEquals(POD_ENDPOINT, staging.effectivePodEndpoint());
  }

  @Test
  void effectivePodEndpointFallsBackToTheEndpointWhenNoPodEndpointIsSet() {
    var staging = StagingConfiguration.builder().endpoint(NODE_ENDPOINT).build();

    assertNull(staging.podEndpoint(), "the default must stay unset");
    assertEquals(NODE_ENDPOINT, staging.effectivePodEndpoint());
  }

  @Test
  void effectivePodEndpointFallsBackToTheEndpointWhenThePodEndpointIsBlank() {
    var staging = StagingConfiguration.builder()
      .endpoint(NODE_ENDPOINT)
      .podEndpoint("")
      .build();

    assertEquals(NODE_ENDPOINT, staging.effectivePodEndpoint(),
      "an empty podEndpoint in config.json means the shared endpoint");
  }
}
