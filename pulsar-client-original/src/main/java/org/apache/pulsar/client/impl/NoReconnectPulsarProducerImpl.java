package org.apache.pulsar.client.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.conf.ProducerConfigurationData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class NoReconnectPulsarProducerImpl<T> extends ProducerImpl<T> {

    private static final Method closeProducerTasksMethod;
    private static final Method failPendingMessagesMethod;

    static {
        // FIXME hack!! -- we need to call these private parent methods to clear some netty timers
        //  (which are also private), otherwise there will be a memory leak
        try {
            closeProducerTasksMethod = ProducerImpl.class.getDeclaredMethod("closeProducerTasks");
            failPendingMessagesMethod = ProducerImpl.class.getDeclaredMethod("failPendingMessages", ClientCnx.class, PulsarClientException.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        closeProducerTasksMethod.setAccessible(true);
        failPendingMessagesMethod.setAccessible(true);
    }

    public NoReconnectPulsarProducerImpl(PulsarClientImpl client,
                                         String topic,
                                         ProducerConfigurationData conf,
                                         CompletableFuture<Producer<T>> producerCreatedFuture,
                                         int partitionIndex, Schema<T> schema,
                                         ProducerInterceptors interceptors) {
        super(client, topic, conf, producerCreatedFuture, partitionIndex, schema, interceptors);
    }

    @Override
    void reconnectLater(Throwable exception) {
        if (exception != null) {
            log.error("Could not connect producer to the broker: {}", exception.getMessage());
            producerCreatedFuture.completeExceptionally(exception);
            failPendingMessagesWithFailedState(exception);
            invokeCloseProducerTaskMethod();
            getClient().cleanupProducer(this);
        }
        super.reconnectLater(exception);
    }

    /**
     * Fails all pending messages and completes their futures exceptionally; without this call they could hang indefinitely once the timeout is cancelled by the {@code closeProducerTasks} method call.
     * The {@code failPendingMessages} call must be synchronized according to the documentation, also
     * state must be set to Failed before invoking {@code failPendingMessages}, otherwise we may have a race condition between adding new messages and failing existing ones.
     */
    private synchronized void failPendingMessagesWithFailedState(Throwable exception) {
        setState(State.Failed);
        invokeFailPendingMessagesMethod(exception);
    }

    private void invokeFailPendingMessagesMethod(Throwable exception) {
        try {
            PulsarClientException pce = exception instanceof PulsarClientException
                    ? (PulsarClientException) exception
                    : new PulsarClientException(exception);
            failPendingMessagesMethod.invoke(this, null, pce);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeCloseProducerTaskMethod() {
        try {
            closeProducerTasksMethod.invoke(this);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
