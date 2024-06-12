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
package org.cometd.server.transport;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>HTTP ServerTransport base class, used by ServerTransports that use
 * HTTP as transport or to initiate a transport connection.</p>
 */
public abstract class AbstractHttpTransport extends AbstractServerTransport {
    public final static String PREFIX = "long-polling";
    public static final String JSON_DEBUG_OPTION = "jsonDebug";
    public static final String MESSAGE_PARAM = "message";
    public final static String AUTOBATCH_OPTION = "autoBatch";
    public final static String TRUST_CLIENT_SESSION = "trustClientSession";

    protected final Logger _logger = LoggerFactory.getLogger(getClass());
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<>();
    private final Collection<LongPollScheduler> _schedulers = new CopyOnWriteArrayList<>();
    private boolean _autoBatch;
    private boolean _trustClientSession;
    private Integer _heartbeatMinutes;

    protected AbstractHttpTransport(BayeuxServerImpl bayeux, String name, Integer heartbeatMinutes) {
        super(bayeux, name);
        setOptionPrefix(PREFIX);
        this._heartbeatMinutes = heartbeatMinutes;
    }

    @Override
    public void init() {
        super.init();
        _autoBatch = getOption(AUTOBATCH_OPTION, true);
        _trustClientSession = getOption(TRUST_CLIENT_SESSION, true);
    }

    protected Collection<LongPollScheduler> getSchedulers() {
        return _schedulers;
    }

    protected boolean isAutoBatch() {
        return _autoBatch;
    }

    public void setCurrentRequest(HttpServletRequest request) {
        _currentRequest.set(request);
    }

    public HttpServletRequest getCurrentRequest() {
        return _currentRequest.get();
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    protected abstract HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout);

    protected abstract void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies);

    protected void processMessages(HttpServletRequest request, HttpServletResponse response, ServerMessage.Mutable[] messages) throws IOException {
        ServerSessionImpl session = null;
        boolean autoBatch = isAutoBatch();
        boolean batch = false;
        boolean sendQueue = true;
        boolean sendReplies = true;
        boolean startInterval = false;
        try {
            for (int i = 0; i < messages.length; ++i) {
                ServerMessage.Mutable message = messages[i];
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Processing {}", message);
                }

                Object clientId = message.get("clientId");
                _logger.debug("recived message from {} >> {}", clientId, message.getJSON());
                if (clientId != null && !(clientId instanceof String)) {
                    throw new IllegalArgumentException("clientId must be a String value");
                }

                if (session == null && _trustClientSession) {
                    session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());
                }

                if (session != null) {
                    if (session.isHandshook()) {
                        if (autoBatch && !batch) {
                            batch = true;
                            session.startBatch();
                        }
                    } else {
                        // Disconnected concurrently.
                        if (batch) {
                            batch = false;
                            session.endBatch();
                        }
                        session = null;
                    }
                }

                Object channelValue = message.get("channel");

                if (!(channelValue instanceof String)) {
                    response.sendError(400, "Channel not specified.");
                    error(request, response, null, response.getStatus());
                } else {
                    String channel = (String) channelValue;
                    switch (channel) {
                        case Channel.META_HANDSHAKE: {
                            if (messages.length > 1) {
                                throw new IOException();
                            }
                            ServerMessage.Mutable reply = processMetaHandshake(request, response, session, message);
                            if (reply != null) {
                                session = (ServerSessionImpl) getBayeux().getSession(reply.getClientId());
                            }
                            messages[i] = processReply(session, reply);
                            sendQueue = false;
                            break;
                        }
                        case Channel.META_CONNECT: {
                            ServerMessage.Mutable reply = processMetaConnect(request, response, session, message);
                            messages[i] = processReply(session, reply);
                            startInterval = sendQueue = sendReplies = reply != null;
                            break;
                        }
                        default: {
                            ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
                            messages[i] = processReply(session, reply);
                            break;
                        }
                    }
                }
            }

            if (sendReplies || sendQueue) {
                flush(request, response, session, sendQueue, startInterval, messages);
            }
        } finally {
            if (batch) {
                session.endBatch();
            }
        }
    }

    protected ServerMessage.Mutable processMetaHandshake(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable message) {
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        return reply;
    }

    protected ServerMessage.Mutable processMetaConnect(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable message) {
        if (session != null) {
            // Cancel the previous scheduler to cancel any prior waiting long poll.
            // This should also decrement the browser ID.
            session.setScheduler(null);
        }

        boolean wasConnected = session != null && session.isConnected();
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null && session != null) {
            if (!session.hasNonLazyMessages() && reply.isSuccessful()) {
                long timeout = session.calculateTimeout(getTimeout());

                // Support old clients that do not send advice:{timeout:0} on the first connect
                if (timeout > 0 && wasConnected && session.isConnected()) {
                    // Between the last time we checked for messages in the queue
                    // (which was false, otherwise we would not be in this branch)
                    // and now, messages may have been added to the queue.
                    // We will suspend anyway, but setting the scheduler on the
                    // session will decide atomically if we need to resume or not.

                    HttpScheduler scheduler = suspend(request, response, session, reply, timeout);
                    metaConnectSuspended(request, response, scheduler.getAsyncContext(), session);
                    // Setting the scheduler may resume the /meta/connect
                    session.setScheduler(scheduler);
                    reply = null;
                }
            }

            if (reply != null && session.isDisconnected()) {
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            }
        }

        return reply;
    }

    protected ServerMessage.Mutable processReply(ServerSessionImpl session, ServerMessage.Mutable reply) {
        if (reply != null) {
            reply = getBayeux().extendReply(session, session, reply);
            if (reply != null) {
                getBayeux().freeze(reply);
            }
        }
        return reply;
    }

    protected void flush(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean sendQueue, boolean startInterval, ServerMessage.Mutable... replies) {
        List<ServerMessage> messages = Collections.emptyList();
        if (session != null) {
            boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
            if (sendQueue && (startInterval || !metaConnectDelivery)) {
                messages = session.takeQueue();
            }
        }

        write(request, response, session, startInterval, messages, replies);
    }

    protected void resume(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, LongPollScheduler scheduler) {
        ServerSessionImpl session = scheduler.getServerSession();
        metaConnectResumed(request, response, asyncContext, session);
        ServerMessage.Mutable reply = scheduler.getMetaConnectReply();
        Map<String, Object> advice = session.takeAdvice(this);
        if (advice != null) {
            reply.put(Message.ADVICE_FIELD, advice);
        }
        if (session.isDisconnected()) {
            reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }

        flush(request, response, session, true, true, processReply(session, reply));
    }

    public BayeuxContext getContext() {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            return new HttpContext(request);
        }
        return null;
    }

    protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws IOException {
        _logger.warn("Could not parse JSON: " + json, exception);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    protected void handleInvalidMessage(HttpServletResponse response, Exception exc) {
        _logger.debug("Could not parse message.", exc);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        try (Writer out = response.getWriter()) {
            out.write(exc.getMessage());
        } catch (Exception e) {
            _logger.error("Exception when trying to write Exception message", e);
        }
    }

    protected void error(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, int responseCode) {
        try {
            response.setStatus(responseCode);
        } catch (Exception x) {
            _logger.trace("Could not send " + responseCode + " response", x);
        } finally {
            try {
                if (asyncContext != null) {
                    asyncContext.complete();
                }
            } catch (Exception x) {
                _logger.trace("Could not complete " + responseCode + " response", x);
            }
        }
    }

    protected ServerMessage.Mutable bayeuxServerHandle(ServerSessionImpl session, ServerMessage.Mutable message) {
        return getBayeux().handle(session, message);
    }

    protected void metaConnectSuspended(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSession session) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Suspended request {}", request);
        }
    }

    protected void metaConnectResumed(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSession session) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Resumed request {}", request);
        }
    }

    /**
     * Sweeps the transport for old Browser IDs
     */
    protected void sweep() {
        for (LongPollScheduler scheduler : _schedulers) {
            scheduler.validate();
        }
    }

    private static class HttpContext implements BayeuxContext {
        final HttpServletRequest _request;

        HttpContext(HttpServletRequest request) {
            _request = request;
        }

        public Principal getUserPrincipal() {
            return _request.getUserPrincipal();
        }

        public boolean isUserInRole(String role) {
            return _request.isUserInRole(role);
        }

        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(_request.getRemoteHost(), _request.getRemotePort());
        }

        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(_request.getLocalName(), _request.getLocalPort());
        }

        public String getHeader(String name) {
            return _request.getHeader(name);
        }

        public List<String> getHeaderValues(String name) {
            return Collections.list(_request.getHeaders(name));
        }

        public String getParameter(String name) {
            return _request.getParameter(name);
        }

        public List<String> getParameterValues(String name) {
            return Arrays.asList(_request.getParameterValues(name));
        }

        public String getCookie(String name) {
            Cookie[] cookies = _request.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if (name.equals(c.getName())) {
                        return c.getValue();
                    }
                }
            }
            return null;
        }

        public String getHttpSessionId() {
            HttpSession session = _request.getSession(false);
            if (session != null) {
                return session.getId();
            }
            return null;
        }

        public Object getHttpSessionAttribute(String name) {
            HttpSession session = _request.getSession(false);
            if (session != null) {
                return session.getAttribute(name);
            }
            return null;
        }

        public void setHttpSessionAttribute(String name, Object value) {
            HttpSession session = _request.getSession(false);
            if (session != null) {
                session.setAttribute(name, value);
            } else {
                throw new IllegalStateException("!session");
            }
        }

        public void invalidateHttpSession() {
            HttpSession session = _request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        public Object getRequestAttribute(String name) {
            return _request.getAttribute(name);
        }

        private ServletContext getServletContext() {
            HttpSession s = _request.getSession(false);
            if (s != null) {
                return s.getServletContext();
            } else {
                s = _request.getSession(true);
                ServletContext servletContext = s.getServletContext();
                s.invalidate();
                return servletContext;
            }
        }

        public Object getContextAttribute(String name) {
            return getServletContext().getAttribute(name);
        }

        public String getContextInitParameter(String name) {
            return getServletContext().getInitParameter(name);
        }

        public String getURL() {
            StringBuffer url = _request.getRequestURL();
            String query = _request.getQueryString();
            if (query != null) {
                url.append("?").append(query);
            }
            return url.toString();
        }

        @Override
        public List<Locale> getLocales() {
            return Collections.list(_request.getLocales());
        }
    }

    public interface HttpScheduler extends Scheduler {
        public HttpServletRequest getRequest();

        public HttpServletResponse getResponse();

        public AsyncContext getAsyncContext();
    }

    protected abstract class LongPollScheduler implements Runnable, HttpScheduler, AsyncListener, ServerSession.RemoveListener {
        private final Logger log = LoggerFactory.getLogger(getClass());
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final ServerMessage.Mutable reply;
        private final org.eclipse.jetty.util.thread.Scheduler.Task task;
        private final AtomicBoolean cancel;
        private Duration validTime;
        private Interval lastValidation;

        protected LongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
            this.session = session;
            this.session.addListener(this);
            this.reply = reply;
            asyncContext.addListener(this);
            this.task = getBayeux().schedule(this, timeout);
            this.cancel = new AtomicBoolean();
            validTime = Duration.standardMinutes(_heartbeatMinutes);
            this.lastValidation = new Interval(new DateTime(), validTime);
        }

        @Override
        public HttpServletRequest getRequest() {
            return request;
        }

        @Override
        public HttpServletResponse getResponse() {
            return response;
        }

        @Override
        public AsyncContext getAsyncContext() {
            return asyncContext;
        }

        public ServerSessionImpl getServerSession() {
            return session;
        }

        public ServerMessage.Mutable getMetaConnectReply() {
            return reply;
        }

        public void validate() {
            if (!lastValidation.containsNow()) {
                log.debug("validating session {}", session.getId());
                try {
                    if (!isValid()) {
                        log.debug("long poll interrupted session {}", session.getId());
                        cancel();
                    }
                    lastValidation = new Interval(new DateTime(), validTime);
                } catch (Exception e) {
                    log.debug("validation error", e);
                }
            }
        }

        private boolean isValid() {
            log.debug("validating session {}  ", session.getId());
            final ServletResponse response = asyncContext.getResponse();
            try {
                response.getOutputStream().print(" ");
                response.getOutputStream().flush();
                return true;
            } catch (IOException e) {
                log.debug("session {} validation failed", session.getId());
                return false;
            }

        }

        private void cleanup() {
            session.setScheduler(null);
            _schedulers.remove(this);
            session.removeListener(this);
            session.deactivate();
        }

        @Override
        public void schedule() {
            if (cancelTimeout()) {
                if (log.isDebugEnabled()) {
                    log.debug("Resuming /meta/connect after schedule");
                }
                resume();
            }
        }

        @Override
        public void cancel() {
            log.debug("aborting {} - {}", session.getId(), this);
            cleanup();
            if (cancelTimeout() && asyncContext != null) {
                final HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
                try {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getOutputStream().print("");
                    response.flushBuffer();
                } catch (IOException e) {
                    log.debug("Cancel for {} failed", session.getId());
                }
                try {
                    asyncContext.complete();
                } catch (Exception x) {
                    log.trace("", x);
                }
            }
        }

        private boolean cancelTimeout() {
            // Cannot rely on the return value of task.cancel()
            // since it may be invoked when the task is in run()
            // where cancellation is not possible (it's too late).
            boolean cancelled = cancel.compareAndSet(false, true);
            task.cancel();
            return cancelled;
        }

        @Override
        public void run() {
            if (cancelTimeout()) {
                session.setScheduler(null);
                if (log.isDebugEnabled()) {
                    log.debug("Resuming /meta/connect after timeout");
                }
                resume();
            }
        }

        private void resume() {
            dispatch();
            cleanup();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            session.setScheduler(null);
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException {
            cleanup();
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        @Override
        public void removed(ServerSession session, boolean timeout) {
            cleanup();
        }

        protected abstract void dispatch();

        protected void error(int code) {
            AbstractHttpTransport.this.error(getRequest(), getResponse(), getAsyncContext(), code);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((asyncContext == null) ? 0 : asyncContext.hashCode());
            result = prime * result + ((reply == null) ? 0 : reply.hashCode());
            result = prime * result + ((session == null) ? 0 : session.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LongPollScheduler other = (LongPollScheduler) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (asyncContext == null) {
                if (other.asyncContext != null)
                    return false;
            } else if (!asyncContext.equals(other.asyncContext))
                return false;
            if (reply == null) {
                if (other.reply != null)
                    return false;
            } else if (!reply.equals(other.reply))
                return false;
            if (session == null) {
                if (other.session != null)
                    return false;
            } else if (!session.equals(other.session))
                return false;
            return true;
        }

        private AbstractHttpTransport getOuterType() {
            return AbstractHttpTransport.this;
        }
    }
}
