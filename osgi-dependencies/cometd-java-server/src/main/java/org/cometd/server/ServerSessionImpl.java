/*
 * Copyright (c) 2008-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.*;
import org.cometd.common.HashMapMessage;
import org.cometd.server.AbstractServerTransport.Scheduler;
import org.cometd.server.transport.AbstractHttpTransport;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.cometd.server.SessionState.*;

public class ServerSessionImpl implements ServerSession, Dumpable {
    private static final int DEFAULT_INACTIVE_INTERVAL = 30;

    private static final AtomicLong _idCount = new AtomicLong();

    private static final Logger _logger = LoggerFactory.getLogger(ServerSession.class);
    private final BayeuxServerImpl _bayeux;
    private final String _id;
    private final List<ServerSessionListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final Queue<ServerMessage> _queue = new ArrayDeque<>();
    private final LocalSessionImpl _localSession;
    private final AttributesMap _attributes = new AttributesMap();
    private final AtomicReference<SessionState> _sessionState = new AtomicReference<>(UNINITILIZED);
    private final Map<ServerChannelImpl, Boolean> _subscribedTo = new ConcurrentHashMap<>();
    private final LazyTask _lazyTask = new LazyTask();
    private volatile Scheduler _scheduler;
    private ServerTransport _advisedTransport;
    private int _maxQueue = -1;
    private long _transientTimeout = -1;
    private long _transientInterval = -1;
    private long _timeout = -1;
    private long _interval = -1;
    private long _maxInterval = -1;
    private long _maxServerInterval = -1;
    private long _maxLazy = -1;
    private boolean _metaConnectDelivery;
    private int _batch;
    private String _userAgent;
    private long _connectTimestamp = -1;
    private long _intervalTimestamp;
    private boolean _nonLazyMessages;
    private boolean _broadcastToPublisher;
    private long _inactiveInterval = -1;

    protected ServerSessionImpl(BayeuxServerImpl bayeux) {
        this(bayeux, null, null);
    }

    protected ServerSessionImpl(BayeuxServerImpl bayeux, LocalSessionImpl localSession, String idHint) {
        _bayeux = bayeux;
        _localSession = localSession;

        StringBuilder id = new StringBuilder(30);
        int len = 20;
        if (idHint != null) {
            len += idHint.length() + 1;
            id.append(idHint);
            id.append('_');
        }
        int index = id.length();

        while (id.length() < len) {
            id.append(Long.toString(_bayeux.randomLong(), 36));
        }

        id.insert(index, Long.toString(_idCount.incrementAndGet(), 36));

        _id = id.toString();

        ServerTransport transport = _bayeux.getCurrentTransport();
        if (transport != null) {
            _maxInterval = transport.getMaxInterval();
            _intervalTimestamp = System.currentTimeMillis() + transport.getMaxInterval();
        }

        _broadcastToPublisher = _bayeux.isBroadcastToPublisher();
    }

    /**
     * @return the remote user agent
     */
    public String getUserAgent() {
        return _userAgent;
    }

    /**
     * @param userAgent the remote user agent
     */
    public void setUserAgent(String userAgent) {
        _userAgent = userAgent;
    }

    protected void sweep(long now) {
        if (isLocalSession()) {
            return;
        }
        _logger.trace("try to sweep session {}", getId());
        synchronized (getLock()) {
            if (_intervalTimestamp == 0) {
                if (_maxServerInterval > 0 && now > _connectTimestamp + _maxServerInterval) {
                    _logger.info("Emergency sweeping session {}", this);
                    cancelSchedule();
                    timeout();
                }
            } else if (now > _intervalTimestamp) {
                _logger.debug("sweeping session {}", this);
                cancelSchedule();
                timeout();
            }
        }
    }

    public Set<ServerChannel> getSubscriptions() {
        return Collections.<ServerChannel>unmodifiableSet(_subscribedTo.keySet());
    }

    public void addExtension(Extension extension) {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension) {
        _extensions.remove(extension);
    }

    public List<Extension> getExtensions() {
        return Collections.unmodifiableList(_extensions);
    }

    public void batch(Runnable batch) {
        startBatch();
        try {
            batch.run();
        } finally {
            endBatch();
        }
    }

    public void deliver(Session sender, ServerMessage.Mutable message) {
        ServerSession session = null;
        if (sender instanceof ServerSession) {
            session = (ServerSession)sender;
        } else if (sender instanceof LocalSession) {
            session = ((LocalSession)sender).getServerSession();
        }

        if (message instanceof ServerMessageImpl) {
            ((ServerMessageImpl)message).setLocal(true);
        }

        if (!_bayeux.extendSend(session, this, message)) {
            return;
        }

        doDeliver(session, message);
    }

    public void deliver(Session sender, String channelId, Object data) {
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setChannel(channelId);
        message.setData(data);
        deliver(sender, message);
    }

    protected void doDeliver(ServerSession sender, ServerMessage.Mutable mutable) {
        _logger.debug("deliver message {} -> {}", getId(), mutable);

        if (sender == this && !isBroadcastToPublisher()) {
            return;
        }

        ServerMessage.Mutable message = extendSend(mutable);
        if (message == null) {
            return;
        }

        _bayeux.freeze(message);

        if (!_listeners.isEmpty()) {
            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof MessageListener) {
                    if (!notifyOnMessage((MessageListener)listener, sender, message)) {
                        return;
                    }
                }
            }
        }

        Boolean wakeup = enqueueMessage(sender, message);
        if (wakeup == null) {
            return;
        }

        if (wakeup) {
            if (message.isLazy()) {
                flushLazy(message);
            } else {
                flush();
            }
        }
    }

    private Boolean enqueueMessage(ServerSession sender, ServerMessage.Mutable message) {
        synchronized (getLock()) {
            if (!_listeners.isEmpty()) {
                for (ServerSessionListener listener : _listeners) {
                    if (listener instanceof MaxQueueListener) {
                        final int maxQueueSize = _maxQueue;
                        if (maxQueueSize > 0 && _queue.size() > maxQueueSize) {
                            if (!notifyQueueMaxed((MaxQueueListener)listener, this, _queue, sender, message)) {
                                return null;
                            }
                        }
                    }

                }
            }
            addMessage(message);
            if (!_listeners.isEmpty()) {
                for (ServerSessionListener listener : _listeners) {
                    if (listener instanceof QueueListener) {
                        notifyQueued((QueueListener)listener, sender, message);
                    }
                }
            }
            return _batch == 0;
        }
    }

    protected ServerMessage.Mutable extendSend(ServerMessage.Mutable mutable) {
        ServerMessage.Mutable message = null;
        if (mutable.isMeta()) {
            if (extendSendMeta(mutable)) {
                message = mutable;
            }
        } else {
            message = extendSendMessage(mutable);
        }
        return message;
    }

    private boolean notifyQueueMaxed(MaxQueueListener listener, ServerSession session, Queue<ServerMessage> queue, ServerSession sender, ServerMessage message) {
        _logger.debug("queue exceeded max size session : {}", getId());
        try {
            return listener.queueMaxed(session, queue, sender, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    private boolean notifyOnMessage(MessageListener listener, ServerSession from, ServerMessage message) {
        try {
            return listener.onMessage(this, from, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    private void notifyQueued(QueueListener listener, ServerSession session, ServerMessage message) {
        try {
            listener.queued(session, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected void handshake() {
        _logger.debug("changing session {} state {} -> {}", getId(), _sessionState.get(), INITIALIZED);
        _sessionState.set(INITIALIZED);
        AbstractServerTransport transport = (AbstractServerTransport)_bayeux.getCurrentTransport();

        if (transport != null) {
            _maxQueue = transport.getOption(AbstractServerTransport.MAX_QUEUE_OPTION, -1);
            _maxInterval = transport.getMaxInterval();
            _maxServerInterval = transport.getOption("maxServerInterval", -1);
            _maxLazy = transport.getMaxLazyTimeout();
            _inactiveInterval = transport.getOption("inactiveInterval", TimeUnit.MINUTES.toMillis(DEFAULT_INACTIVE_INTERVAL));
        }
    }

    protected void connected() {
        _logger.debug("changing session {} state {} -> {}", getId(), _sessionState.get(), INACTIVE);
        _sessionState.set(INACTIVE);
        cancelIntervalTimeout();
    }

    public void disconnect() {
        _logger.debug("disconnect {}", getId());
        if (isConnected() && _scheduler != null) {
            _scheduler.cancel();
        }
        remove();
    }

    private boolean remove() {
        _logger.debug("remove session {}", getId());
        return _bayeux.removeServerSession(this, false);
    }

    private boolean timeout() {
        _logger.debug("remove timeouted session {}", getId());
        return _bayeux.removeServerSession(this, true);
    }

    public boolean endBatch() {
        boolean result = false;
        synchronized (getLock()) {
            if (--_batch == 0 && _nonLazyMessages) {
                result = true;
            }
        }
        if (result) {
            flush();
        }
        return result;
    }

    public LocalSession getLocalSession() {
        return _localSession;
    }

    public boolean isLocalSession() {
        return _localSession != null;
    }

    public void startBatch() {
        synchronized (getLock()) {
            ++_batch;
        }
    }

    public void addListener(ServerSessionListener listener) {
        _listeners.add(listener);
    }

    public String getId() {
        return _id;
    }

    public Object getLock() {
        return this;
    }

    public Queue<ServerMessage> getQueue() {
        return _queue;
    }

    public boolean hasNonLazyMessages() {
        synchronized (getLock()) {
            return _nonLazyMessages;
        }
    }

    public void addMessage(ServerMessage message) {
        _logger.debug("enqueue message {} - {}", getId(), message.getJSON());
        synchronized (getLock()) {
            _queue.add(message);
            _nonLazyMessages |= !message.isLazy();
        }
    }

    public List<ServerMessage> takeQueue() {
        List<ServerMessage> copy = Collections.emptyList();

        synchronized (getLock()) {
            // Always call listeners, even if the queue is
            // empty since they may add messages to the queue.
            if (!_listeners.isEmpty()) {
                for (ServerSessionListener listener : _listeners) {
                    if (listener instanceof DeQueueListener) {
                        notifyDeQueue((DeQueueListener)listener, this, _queue);
                    }
                }
            }

            int size = _queue.size();
            if (size > 0) {
                copy = new ArrayList<>(size);
                copy.addAll(_queue);
                _queue.clear();
            }

            _nonLazyMessages = false;
        }
        return copy;
    }

    private void notifyDeQueue(DeQueueListener listener, ServerSession serverSession, Queue<ServerMessage> queue) {
        try {
            listener.deQueue(serverSession, queue);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    public void removeListener(ServerSessionListener listener) {
        _listeners.remove(listener);
    }

    public List<ServerSessionListener> getListeners() {
        return Collections.unmodifiableList(_listeners);
    }

    public void setScheduler(Scheduler newScheduler) {
        _logger.debug("reschedule schedule {} - {}", getId(), newScheduler);
        synchronized (getLock()) {
            cancelSchedule();
            _scheduler = newScheduler;
            if (hasNonLazyMessages() && _batch == 0) {
                if (newScheduler instanceof AbstractHttpTransport.HttpScheduler) {
                    _logger.debug("schedule interupted sending pending messages");
                    _scheduler = null;
                    newScheduler.schedule();
                }
            }
            if (newScheduler != null) {
                activate();
            }
        }
    }

    public void flush() {
        Scheduler scheduler;
        synchronized (getLock()) {
            _lazyTask.cancel();

            scheduler = _scheduler;

            if (scheduler != null) {
                if (scheduler instanceof AbstractHttpTransport.HttpScheduler) {
                    _scheduler = null;
                }
            }
        }
        if (scheduler != null) {
            scheduler.schedule();
            // If there is a scheduler, then it's a remote session
            // and we should not perform local delivery, so we return
            return;
        }

        // do local delivery
        if (_localSession != null && hasNonLazyMessages()) {
            for (ServerMessage msg : takeQueue()) {
                if(msg instanceof WeakMessage) {
                    _localSession.receive( ((WeakMessage) msg).copy());
                } else {
                    _localSession.receive(new HashMapMessage(msg));
                }
            }
        }
    }

    private void flushLazy(ServerMessage message) {
        synchronized (getLock()) {
            ServerChannel channel = _bayeux.getChannel(message.getChannel());
            long lazyTimeout = -1;
            if (channel != null) {
                lazyTimeout = channel.getLazyTimeout();
            }
            if (lazyTimeout <= 0) {
                lazyTimeout = _maxLazy;
            }

            if (lazyTimeout <= 0) {
                flush();
            } else {
                _lazyTask.schedule(lazyTimeout);
            }
        }
    }

    public void cancelSchedule() {
        _logger.debug("cancel schedule {}", getId());
        Scheduler scheduler;
        synchronized (getLock()) {
            deactivate();
            scheduler = _scheduler;
            if (scheduler != null) {
                _scheduler = null;
            }
        }
        if (scheduler != null) {
            scheduler.cancel();
        }
    }

    public void cancelIntervalTimeout() {
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            _connectTimestamp = now;
            _intervalTimestamp = 0;
        }
    }

    public void startIntervalTimeout(long defaultInterval) {
        long interval = calculateInterval(defaultInterval);
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            _intervalTimestamp = now + interval + _maxInterval;
        }
    }

    public SessionState getState() {
        return _sessionState.get();
    }

    protected long getMaxInterval() {
        return _maxInterval;
    }

    long getIntervalTimestamp() {
        return _intervalTimestamp;
    }

    public Object getAttribute(String name) {
        return _attributes.getAttribute(name);
    }

    public Set<String> getAttributeNames() {
        return _attributes.getAttributeNameSet();
    }

    public Object removeAttribute(String name) {
        Object old = getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    public void setAttribute(String name, Object value) {
        _attributes.setAttribute(name, value);
    }

    public boolean isHandshook() {
        final SessionState state = _sessionState.get();
        return state != SessionState.UNINITILIZED;
    }

    public boolean isConnected() {
        return isConnected(_sessionState.get());
    }

    private boolean isConnected(final SessionState state) {
        return state == SessionState.INACTIVE || state == SessionState.ACTIVE;
    }

    public boolean isDisconnected() {
        return _sessionState.get() == SessionState.DISCONNECTED;
    }

    protected boolean extendRecv(ServerMessage.Mutable message) {
        if (!_extensions.isEmpty()) {
            for (Extension extension : _extensions) {
                boolean proceed = message.isMeta() ?
                        notifyRcvMeta(extension, message) :
                        notifyRcv(extension, message);
                if (!proceed) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean notifyRcvMeta(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.rcvMeta(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifyRcv(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.rcv(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    protected boolean extendSendMeta(ServerMessage.Mutable message) {
        if (!message.isMeta()) {
            throw new IllegalStateException();
        }

        if (!_extensions.isEmpty()) {
            for (Extension extension : _extensions) {
                if (!notifySendMeta(extension, message)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean notifySendMeta(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.sendMeta(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    protected ServerMessage.Mutable extendSendMessage(ServerMessage.Mutable message) {
        if (message.isMeta()) {
            throw new IllegalStateException();
        }

        if (!_extensions.isEmpty()) {
            for (Extension extension : _extensions) {
                message = notifySend(extension, message);
                if (message == null) {
                    return null;
                }
            }
        }

        return message;
    }

    private ServerMessage.Mutable notifySend(Extension extension, ServerMessage.Mutable message) {
        try {
            ServerMessage result = extension.send(this, message);
            if (result instanceof ServerMessage.Mutable) {
                return (ServerMessage.Mutable)result;
            } else {
                return result == null ? null : _bayeux.newMessage(result);
            }
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return message;
        }
    }

    public void reAdvise() {
        _advisedTransport = null;
    }

    public Map<String, Object> takeAdvice(ServerTransport transport) {
        if (transport != null && transport != _advisedTransport) {
            _advisedTransport = transport;

            // The timeout is calculated based on the values of the session/transport
            // because we want to send to the client the *next* timeout
            long timeout = getTimeout() < 0 ? transport.getTimeout() : getTimeout();

            // The interval is calculated using also the transient value
            // because we want to send to the client the *current* interval
            long interval = calculateInterval(transport.getInterval());

            Map<String, Object> advice = new HashMap<>(3);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
            advice.put(Message.INTERVAL_FIELD, interval);
            advice.put(Message.TIMEOUT_FIELD, timeout);
            return advice;
        }

        // advice has not changed, so return null.
        return null;
    }

    public long getTimeout() {
        return _timeout;
    }

    public long getInterval() {
        return _interval;
    }

    public void setTimeout(long timeoutMS) {
        _timeout = timeoutMS;
        _advisedTransport = null;
    }

    public void setInterval(long intervalMS) {
        _interval = intervalMS;
        _advisedTransport = null;
    }

    public boolean isBroadcastToPublisher() {
        return _broadcastToPublisher;
    }

    public void setBroadcastToPublisher(boolean value) {
        _broadcastToPublisher = value;
    }

    /**
     * @param timedOut whether the session has been timed out
     * @return True if the session was connected.
     */
    protected boolean removed(boolean timedOut) {
        _logger.debug("changing session {} state {} -> {}", getId(), _sessionState.get(), !timedOut ? DISCONNECTED : TIMEOUTED);
        SessionState state = _sessionState.getAndSet(!timedOut ? DISCONNECTED : TIMEOUTED);
        if (state != UNINITILIZED) {
            for (ServerChannelImpl channel : _subscribedTo.keySet()) {
                channel.unsubscribe(this);
            }

            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof RemoveListener) {
                    notifyRemoved((RemoveListener)listener, this, timedOut);
                }
            }
            cancelSchedule();
        }
        return isConnected(state);
    }

    private void notifyRemoved(RemoveListener listener, ServerSession serverSession, boolean timedout) {
        try {
            listener.removed(serverSession, timedout);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    public void setMetaConnectDeliveryOnly(boolean meta) {
        _metaConnectDelivery = meta;
    }

    public boolean isMetaConnectDeliveryOnly() {
        return _metaConnectDelivery;
    }

    protected void subscribedTo(ServerChannelImpl channel) {
        _subscribedTo.put(channel, Boolean.TRUE);
    }

    protected void unsubscribedFrom(ServerChannelImpl channel) {
        _subscribedTo.remove(channel);
    }

    public long calculateTimeout(long defaultTimeout) {
        if (_transientTimeout >= 0) {
            return _transientTimeout;
        }

        if (_timeout >= 0) {
            return _timeout;
        }

        return defaultTimeout;
    }

    public long calculateInterval(long defaultInterval) {
        if (_transientInterval >= 0) {
            return _transientInterval;
        }

        if (_interval >= 0) {
            return _interval;
        }

        return defaultInterval;
    }

    /**
     * Updates the transient timeout with the given value.
     * The transient timeout is the one sent by the client, that should
     * temporarily override the session/transport timeout, for example
     * when the client sends {timeout:0}
     *
     * @param timeout the value to update the timeout to
     * @see #updateTransientInterval(long)
     */
    public void updateTransientTimeout(long timeout) {
        _transientTimeout = timeout;
    }

    /**
     * Updates the transient interval with the given value.
     * The transient interval is the one sent by the client, that should
     * temporarily override the session/transport interval, for example
     * when the client sends {timeout:0,interval:60000}
     *
     * @param interval the value to update the interval to
     * @see #updateTransientTimeout(long)
     */
    public void updateTransientInterval(long interval) {
        _transientInterval = interval;
    }

    public void activate() {
        _logger.debug("changing session {} state {} -> {}", getId(), _sessionState.get(), ACTIVE);
        if (_sessionState.getAndSet(ACTIVE) != ACTIVE) {
            synchronized (_queue) {
                _intervalTimestamp = System.currentTimeMillis() + _maxInterval;
            }
        }
    }

    public void deactivate() {
        _logger.debug("changing session {} state {} -> {}", getId(), _sessionState.get(), INACTIVE);
        if (_sessionState.getAndSet(INACTIVE) != INACTIVE) {
            synchronized (_queue) {
                _intervalTimestamp = System.currentTimeMillis() + calculateInterval(0) + _inactiveInterval;
            }
        }
    }

    @Override
    public String dump() {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        ContainerLifeCycle.dumpObject(out, this);

        List<Object> children = new ArrayList<>();

        children.add(new Dumpable() {
            @Override
            public String dump() {
                return null;
            }

            @Override
            public void dump(Appendable out, String indent) throws IOException {
                List<ServerSessionListener> listeners = getListeners();
                ContainerLifeCycle.dumpObject(out, "listeners: " + listeners.size());
                if (_bayeux.isDetailedDump()) {
                    ContainerLifeCycle.dump(out, indent, listeners);
                }
            }
        });

        ContainerLifeCycle.dump(out, indent, children);
    }

    @Override
    public String toString() {
        long connect;
        long expire;
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            connect = now - _connectTimestamp;
            expire = _intervalTimestamp == 0 ? 0 : _intervalTimestamp - now;
        }
        return String.format("%s,connect=%d,expire=%d", _id, connect, expire);
    }

    private class LazyTask implements Runnable {
        private long _execution;
        private volatile org.eclipse.jetty.util.thread.Scheduler.Task _task;

        @Override
        public void run() {
            flush();
            _execution = 0;
            _task = null;
        }

        public boolean cancel() {
            org.eclipse.jetty.util.thread.Scheduler.Task task = _task;
            return task != null && task.cancel();
        }

        public boolean schedule(long lazyTimeout) {
            long execution = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lazyTimeout);
            if (_task == null || execution < _execution) {
                cancel();
                _execution = execution;
                _task = _bayeux.schedule(this, lazyTimeout);
                return true;
            }
            return false;
        }
    }
}
