package org.apache.pulsar.client.impl;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class NoReconnectPulsarClientBuilderTest {

    @Test
    public void buildDisablesClientSideMemoryLimit() throws PulsarClientException {
        NoReconnectPulsarClientBuilder builder = NoReconnectPulsarClientBuilder.noReconnectPulsarClientBuilder();
        builder.serviceUrl("pulsar://localhost:6650");

        PulsarClient client = builder.build();
        try {
            assertThat(builder.conf.getMemoryLimitBytes()).isZero();
        } finally {
            client.close();
        }
    }
}
