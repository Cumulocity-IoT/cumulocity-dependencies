package org.apache.pulsar.client.impl;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.ProducerBusyException;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.conf.ProducerConfigurationData;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.pulsar.client.api.Schema.BYTES;
import static org.apache.pulsar.client.impl.HandlerState.State.Connecting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoReconnectPulsarProducerImplTest {

    /**
     * Core regression test: all futures in {@code pendingMessages} must complete exceptionally
     * when {@code reconnectLater()} is called.
     *
     * <p>Without the fix, {@code closeProducerTasks()} cancels the send-timeout timer without
     * sweeping the queue — futures are permanently orphaned. With the fix,
     * {@code failPendingMessagesWithFailedState()} completes them before the timer is cancelled.
     */
    @Test
    void shouldCompleteAllPendingFuturesExceptionallyWhenReconnectLaterCalled() {
        // Given
        NoReconnectPulsarProducerImpl<byte[]> producer = createProducer();

        List<CompletableFuture<MessageId>> futures = queueMessages(producer, 50);

        assertThat(futures).allMatch(future -> !future.isDone());
        assertThat(producer.getPendingQueueSize()).isEqualTo(50);

        // When
        producer.reconnectLater(new ProducerBusyException("Pulsar issue"));

        // Then
        assertThat(futures).allSatisfy(future -> Assertions.assertThat(future)
                .completesExceptionallyWithin(1, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(PulsarClientException.class));
    }

    // Creates a producer with no real broker connection. grabCnx() is overridden to be a no-op,
    // and state is set to Connecting so sendAsync() accepts messages into pendingMessages
    // without attempting to write them to a socket.
    private NoReconnectPulsarProducerImpl<byte[]> createProducer() {
        NoReconnectPulsarProducerImpl<byte[]> producer = new NoReconnectPulsarProducerImpl<>(
                pulsarClient(),
                "persistent://public/default/test",
                producerConfig(),
                completedFuture(null),
                -1,
                BYTES,
                null) {
            @Override
            void grabCnx() { /* prevent actual broker connection */ }
        };
        producer.setState(Connecting);
        return producer;
    }

    private PulsarClientImpl pulsarClient() {
        MemoryLimitController memoryLimitController = mock(MemoryLimitController.class);
        when(memoryLimitController.tryReserveMemory(anyLong())).thenReturn(true);

        ClientConfigurationData clientConfig = new ClientConfigurationData();
        clientConfig.setStatsIntervalSeconds(0); // disable stats recorder

        PulsarClientImpl client = mock(PulsarClientImpl.class);
        when(client.getConfiguration()).thenReturn(clientConfig);
        when(client.getMemoryLimitController()).thenReturn(memoryLimitController);
        when(client.getClientClock()).thenReturn(Clock.systemUTC());

        return client;
    }

    private ProducerConfigurationData producerConfig() {
        ProducerConfigurationData producerConfig = new ProducerConfigurationData();
        producerConfig.setTopicName("persistent://public/default/test");
        producerConfig.setProducerName("test-producer");
        producerConfig.setSendTimeoutMs(0);      // disable send-timeout timer
        producerConfig.setMaxPendingMessages(100);
        producerConfig.setBatchingEnabled(false);
        return producerConfig;
    }

    private List<CompletableFuture<MessageId>> queueMessages(NoReconnectPulsarProducerImpl<byte[]> producer, int count) {
        return IntStream.range(0, count).mapToObj(i -> producer.sendAsync("msg".getBytes())).toList();
    }
}
