package org.cometd.server;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.jayway.jsonpath.JsonPath;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.server.transport.AbstractHttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cometd.server.SessionState.*;
import static org.joda.time.DateTimeUtils.currentTimeMillis;
import static org.joda.time.DateTimeUtils.setCurrentMillisFixed;

public class ServerSessionImplTest {

    BayeuxServerImpl server = new BayeuxServerImpl();

    @BeforeEach
    public void setup() throws Exception {
        server.setOption("long-polling.maxInterval", 1000000);
        server.start();
    }

    @Test
    public void shouldHandshakeClientAndAssignClientIdAndSessionShouldBeInitialized() throws IOException, ServletException {
        //Given
        AbstractHttpTransport transport = transport();
        Response response = new Response();

        //When
        transport.handle(request().handshake().build(), response.build());

        String clientId = response.resolve("$[0].clientId");

        //Then
        assertThat(clientId).isNotNull().isNotEmpty();
        assertThat(((ServerSessionImpl) server.getSession(clientId)).getState()).isEqualTo(INITIALIZED);
    }

    @Test
    public void shouldSwitchSessionToActiveWhenClientConnects() throws IOException, ServletException {
        //Given
        AbstractHttpTransport transport = transport();

        Response handshake = new Response();

        //When
        transport.handle(request().handshake().build(), handshake.build());

        String clientId = handshake.resolve("$[0].clientId");

        Response connect = new Response();

        transport.handle(request().clientId(clientId).connect().build(), connect.build());

        //Then
        assertThat(((ServerSessionImpl) server.getSession(clientId)).getState()).isEqualTo(ACTIVE);
    }

    @Test
    public void shouldSwitchSessionToInactiveWhenConnectionWasClosed() throws IOException, ServletException {
        //Given
        AbstractHttpTransport transport = transport();
        Response handshake = new Response();

        //When
        transport.handle(request().handshake().build(), handshake.build());

        String clientId = handshake.resolve("$[0].clientId");

        Response connect = new Response();

        transport.handle(request().clientId(clientId).connect().build(), connect.invalidConnection().build());
        setCurrentMillisFixed(currentTimeMillis() + TimeUnit.MINUTES.toMillis(30));
        server.sweep();
        setCurrentMillisFixed(currentTimeMillis() + TimeUnit.MINUTES.toMillis(30));
        server.sweep();

        //Then
        assertThat(((ServerSessionImpl) server.getSession(clientId)).getState()).isEqualTo(INACTIVE);
    }

    @Test
    public void shouldNotSendMessageToInvalidateSession() throws IOException, ServletException {
        //Given
        AbstractHttpTransport transport = transport();
        Response handshake = new Response();

        //When
        transport.handle(request().handshake().build(), handshake.build());

        String clientId = handshake.resolve("$[0].clientId");

        transport.handle(request().clientId(clientId).subscribe("/some/channel").build(), new Response().build());

        Response connect = new Response();
        transport.handle(request().clientId(clientId).connect().build(), connect.invalidConnection().build());

        setCurrentMillisFixed(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30));
        server.sweep();

        LocalSession test = server.newLocalSession("test");
        test.handshake();
        test.getChannel("/some/channel").publish(new HashMap<>());
        test.disconnect();
        //Then
        assertThat(((ServerSessionImpl) server.getSession(clientId)).getState()).isEqualTo(INACTIVE);
        assertThat(((ServerSessionImpl) server.getSession(clientId)).getQueue()).isNotEmpty();
    }

    private AbstractHttpTransport transport() {
        AbstractHttpTransport transport = (AbstractHttpTransport) server.getTransport("long-polling");
        server.setCurrentTransport(transport);
        return transport;
    }

    private RequestBuilder request() {
        return new RequestBuilder();
    }

    class RequestBuilder {

        private String content;
        private String clientId;

        public HttpServletRequest build() {
            MockHttpServletRequestWrapper request = new MockHttpServletRequestWrapper(new MockHttpServletRequest());
            if (content != null) {
                request.setContent(content.getBytes());
            }
            return request;
        }

        public RequestBuilder handshake() {
            this.content = loadFrom("classpath:templates/handshake.json");
            return this;
        }

        public RequestBuilder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public RequestBuilder connect() {
            this.content = template().inject(loadFrom("classpath:templates/connect.json"));
            return this;
        }

        public RequestBuilder connect(int timeout) {
            this.content = template().with("timeout", timeout).inject(loadFrom("classpath:templates/connect.json"));
            return this;
        }

        private Template template() {
            return new Template().with("clientId", clientId);
        }


        public RequestBuilder subscribe(String subscribe) {
            this.content = template().with("subscribe", subscribe).inject(loadFrom("classpath:templates/subscribe.json"));
            return this;
        }

        private class MockHttpServletRequestWrapper extends HttpServletRequestWrapper {

            MockHttpServletRequest request;
            private byte[] content;

            public MockHttpServletRequestWrapper(MockHttpServletRequest request) {
                super(request);
                this.request = request;
                request.setAsyncSupported(true);
            }

            public void setContent(byte[] content) {
                this.request.setContent(content);
                this.content = content;
            }

            @Override
            public ServletInputStream getInputStream() throws IOException {
                return new MockServletInputStream(new ByteArrayInputStream(this.content));
            }

        }

        private class MockServletInputStream extends ServletInputStream {

            private InputStream sourceStream;
            private int readCount = 0;

            public MockServletInputStream(InputStream sourceStream) {
                this.sourceStream = sourceStream;
            }

            @Override
            public boolean isFinished() {
                return readCount > 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                try {
                    readListener.onDataAvailable();
                    readListener.onAllDataRead();
                } catch (IOException e) {
                    readListener.onError(e);
                }
            }

            @Override
            public int read() throws IOException {
                readCount++;
                return this.sourceStream.read();
            }


            @Override
            public void close() throws IOException {
                super.close();
                this.sourceStream.close();
            }
        }


    }

    class Template {

        private final ImmutableMap.Builder<String, Object> variables = ImmutableMap.<String, Object>builder();

        public Template with(String property, Object value) {
            variables.put(property, value);
            return this;
        }

        public String inject(String template) {
            return environment().resolvePlaceholders(template);
        }

        private StandardEnvironment environment() {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("variables", variables.build()));
            return environment;
        }
    }


    private String loadFrom(final String file) {
        try {
            return new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                    return new DefaultResourceLoader().getResource(file).getInputStream();
                }
            }.asCharSource(Charsets.UTF_8).read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class Response {

        MockHttpServletResponse response = new MockHttpServletResponse();
        private boolean invalidConnection = false;

        public <T> T resolve(String path) {
            try {
                return JsonPath.parse(response.getContentAsString()).read(JsonPath.compile(path));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        public void reset() {
            response.reset();
        }

        public int getStatus() {
            return response.getStatus();
        }

        public String getErrorMessage() {
            return response.getErrorMessage();
        }

        public HttpServletResponse build() {
            try {
                return new MockHttpServletResponseWrapper(response);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        public Response invalidConnection() {
            invalidConnection = true;
            return this;
        }

        private class MockHttpServletResponseWrapper extends HttpServletResponseWrapper {

            private ServletOutputStream outputStream;
            private PrintWriter printWriter;

            public MockHttpServletResponseWrapper(HttpServletResponse response) throws IOException {
                super(response);
                this.outputStream = new MockServletOutputStream(response.getOutputStream());
                this.printWriter = new PrintWriter(this.outputStream);
            }

            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                return outputStream;
            }

            @Override
            public PrintWriter getWriter() throws IOException {
                return printWriter;
            }
        }

        private class MockServletOutputStream extends ServletOutputStream {

            private OutputStream targetStream;

            public MockServletOutputStream(OutputStream targetStream) {
                this.targetStream = targetStream;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                try {
                    writeListener.onWritePossible();
                } catch (IOException e) {
                    writeListener.onError(e);
                }
            }

            @Override
            public void write(int b) throws IOException {
                if (invalidConnection) {
                    throw new IOException("Connection invalid");
                }
                this.targetStream.write(b);
            }

            @Override
            public void flush() throws IOException {
                super.flush();
                this.targetStream.flush();
            }

            @Override
            public void close() throws IOException {
                super.close();
                this.targetStream.close();
            }
        }
    }

}
