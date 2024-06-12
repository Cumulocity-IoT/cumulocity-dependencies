package org.apache.pulsar.client.impl;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.conf.ProducerConfigurationData;

import java.util.concurrent.CompletableFuture;

public class NoReconnectPulsarClientImpl extends PulsarClientImpl {

    public NoReconnectPulsarClientImpl(ClientConfigurationData conf) throws PulsarClientException {
        super(conf);
    }

    protected <T> ProducerImpl<T> newProducerImpl(String topic, int partitionIndex,
                                                  ProducerConfigurationData conf,
                                                  Schema<T> schema,
                                                  ProducerInterceptors interceptors,
                                                  CompletableFuture<Producer<T>> producerCreatedFuture) {
        return new NoReconnectPulsarProducerImpl<>(NoReconnectPulsarClientImpl.this,
                topic,
                conf,
                producerCreatedFuture,
                partitionIndex,
                schema,
                interceptors);
    }
}
