package org.apache.pulsar.client.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.conf.ProducerConfigurationData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class NoReconnectPulsarProducerImpl<T> extends ProducerImpl<T> {

    private static final Method closeProducerTasksMethod;

    static {
        // FIXME hack!! -- we need to call this private parent method to clear some netty timers
        //  (which are also private), otherwise there will be a memory leak
        try {
            closeProducerTasksMethod = ProducerImpl.class.getDeclaredMethod("closeProducerTasks");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        closeProducerTasksMethod.setAccessible(true);
    }

    public NoReconnectPulsarProducerImpl(PulsarClientImpl client,
                                         String topic,
                                         ProducerConfigurationData conf,
                                         CompletableFuture<Producer<T>> producerCreatedFuture,
                                         int partitionIndex,
                                         Schema<T> schema,
                                         ProducerInterceptors interceptors,
                                         Optional<String> overrideProducerName) {
        super(client, topic, conf, producerCreatedFuture, partitionIndex, schema, interceptors, overrideProducerName);
    }

    @Override
    public CompletableFuture<Void> connectionOpened(final ClientCnx cnx) {
        final CompletableFuture<Void> future = super.connectionOpened(cnx);
        final NoReconnectPulsarProducerImpl<T> self = this;
        future.exceptionally(exception -> {
            log.error("Could not connect producer to the broker: {}", exception.getMessage());
            producerCreatedFuture.completeExceptionally(exception);
            invokeCloseProducerTaskMethod();
            setState(State.Failed);
            getClient().cleanupProducer(self);
            return null;
        });
        return future;
    }

    private void invokeCloseProducerTaskMethod() {
        try {
            closeProducerTasksMethod.invoke(this);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
