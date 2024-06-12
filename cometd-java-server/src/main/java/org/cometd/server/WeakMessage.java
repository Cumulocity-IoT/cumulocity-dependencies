package org.cometd.server;

import org.apache.commons.io.IOUtils;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JSONContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.util.Objects.isNull;

public class WeakMessage extends ServerMessageImpl {

    private static final Logger _logger = LoggerFactory.getLogger(WeakMessage.class);
    private transient ServerMessage.Mutable _associated;
    private transient ServerMessage.Mutable frozen;
    private boolean _lazy = false;
    private String _json;
    private transient byte[] _jsonBytes;
    private transient boolean _local;
    private MessageFormat messageFormat;
    private final long _zipMessageSizeThreshold;
    private final JSONContext.Server jsonContext;
    private final boolean ignoreNullDataField;

    public WeakMessage(long zipMessageSizeThreshold, JSONContext.Server jsonContext, boolean ignoreNullDataField) {
        this._zipMessageSizeThreshold = zipMessageSizeThreshold;
        this.jsonContext = jsonContext;
        this.ignoreNullDataField = ignoreNullDataField;
    }

    public WeakMessage(Message message, long zipMessageSizeThreshold, JSONContext.Server jsonContext, boolean ignoreNullDataField) {
        this._zipMessageSizeThreshold = zipMessageSizeThreshold;
        this.putAll(message);
        this.jsonContext = jsonContext;
        this.ignoreNullDataField = ignoreNullDataField;
        if (message.getExt() != null) {
            this.getExt(true).putAll(message.getExt());
        }
    }

    public boolean isLocal() {
        return _local;
    }

    @Override
    public void setLocal(boolean local) {
        _local = local;
    }

    @SuppressWarnings("unchecked")
    protected void freeze() {
        Object data = super.get(DATA_FIELD);
        setWeakReference(this, data);
        frozen = new ServerMessageImpl();
        frozen.setAssociated(super.getAssociated());
        frozen.setLazy(super.isLazy());
        frozen.putAll(this);
        if (data instanceof Map) {
            Map<String, Object> newData = new HashMap<>((Map<String, Object>) data);
            frozen.put(DATA_FIELD, newData);
        } else {
            if (ignoreNullDataField) {
                if (!isNull(data)) {
                    frozen.put(DATA_FIELD, data);
                }
            } else {
                frozen.put(DATA_FIELD, data);
            }
        }
    }

    protected void freeze(String json) {
        throw new UnsupportedOperationException();
    }

    public WeakMessage copy() {
        WeakMessage weakMessage = new WeakMessage(this, this._zipMessageSizeThreshold, this.jsonContext, this.ignoreNullDataField);
        weakMessage._jsonBytes = this._jsonBytes;
        weakMessage._json = this._json;
        weakMessage.messageFormat = this.messageFormat;
        weakMessage.frozen = this.frozen;
        return weakMessage;
    }

    private void setMessageFormat(String json) {
        if (json.getBytes().length > _zipMessageSizeThreshold) {
            _jsonBytes = zipData(json);
            messageFormat = new ZipFormat();
        } else {
            _json = json;
            _jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            messageFormat = new JsonFormat();
        }
    }

    @Override
    protected boolean isFrozen() {
        return frozen != null;
    }

    private boolean isJsonGenerated() {
        return _json != null || _jsonBytes != null;
    }

    @Override
    public String getJSON() {
        serializeIfNeeded();
        return messageFormat.getJSON();
    }

    @Override
    public byte[] getJSONBytes() {
        serializeIfNeeded();
        return messageFormat.getJSONBytes();
    }

    public byte[] getRawData() {
        serializeIfNeeded();
        return _jsonBytes;
    }

    private void serializeIfNeeded() {
        if (!isJsonGenerated()) {
            _json = jsonContext.generate(frozen);
            setWeakReference(frozen, frozen.get(DATA_FIELD));
            setMessageFormat(_json);
        }
    }

    private void setWeakReference(ServerMessage.Mutable message, Object data) {
        if (data instanceof WeakReference) {
            message.put(DATA_FIELD, data);
        } else if (data != null) {
            message.put(DATA_FIELD, new WeakReference(data));
        }
    }

    @Override
    public Object getData() {
        Object data = get(DATA_FIELD);
        if (isFrozen() && data instanceof Map) {
            return Collections.unmodifiableMap((Map<String, Object>) data);
        }
        return data;
    }

    @Override
    public Object get(Object key) {
        if (isFrozen() && DATA_FIELD.equals(key)) {
            Object data = frozen.get(DATA_FIELD);
            data = getDataFromWeakReference(data);
            if (data != null) {
                return data;
            }
        }
        if (isJsonGenerated() && DATA_FIELD.equals(key)) {
            try {
                return getDataFromJson();
            } catch (ParseException e) {
                _logger.error("Error while parsing json data from WeakMessage", e);
            }
            return null;
        }
        return super.get(key);
    }

    Object getDataFromWeakReference(Object data) {
        if (data instanceof WeakReference) {
            data = ((WeakReference) data).get();
        }
        return data;
    }

    @Override
    public void setData(Object data) {
        put(DATA_FIELD, data);
    }

    Object getDataFromJson() throws ParseException {
        return parseJsonToMap(this.getJSON(), this.jsonContext).get(DATA_FIELD);
    }

    static Map parseJsonToMap(String json, JSONContext.Server jsonContext) throws ParseException {
        if (jsonContext != null) {
            return jsonContext.getParser().parse(new StringReader(json), Map.class);
        }
        return new JettyJSONContextServer().getParser().parse(new StringReader(json), Map.class);
    }

    public ServerMessage.Mutable getAssociated() {
        return _associated;
    }

    public void setAssociated(ServerMessage.Mutable associated) {
        _associated = associated;
    }

    public boolean isLazy() {
        return _lazy;
    }

    public void setLazy(boolean lazy) {
        _lazy = lazy;
    }

    public Object getLock() {
        return this;
    }

    @Override
    public Object put(String key, Object value) {
        if (isFrozen()) {
            throw new UnsupportedOperationException();
        }
        return super.put(key, value);
    }

    @Override
    public Map<String, Object> getDataAsMap() {
        Map<String, Object> data = super.getDataAsMap();
        if (isFrozen() && data != null) {
            return Collections.unmodifiableMap(data);
        }
        return data;
    }

    @Override
    public Map<String, Object> getExt(boolean create) {
        Map<String, Object> ext = getExt();
        if (create && ext == null) {
            ext = new HashMap<>();
            put(EXT_FIELD, ext);
        }
        return ext;
    }

    @Override
    public Map<String, Object> getAdvice() {
        Map<String, Object> advice = super.getAdvice();
        if (isFrozen() && advice != null) {
            return Collections.unmodifiableMap(advice);
        }
        return advice;
    }

    byte[] zipData(String json) {
        try {
            ByteArrayOutputStream obj = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(obj);
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
            gzip.flush();
            gzip.close();
            return obj.toByteArray();
        } catch (IOException e) {
            _logger.error("Unable to zip json data", e);
            throw new RuntimeException(e);
        }
    }

    interface MessageFormat {
        String getJSON();

        byte[] getJSONBytes();
    }

    class ZipFormat implements MessageFormat {
        @Override
        public String getJSON() {
            try {
                return IOUtils.toString(new GZIPInputStream(new ByteArrayInputStream(_jsonBytes)));
            } catch (IOException e) {
                _logger.error("Unable to unzip json data", e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public byte[] getJSONBytes() {
            try {
                return IOUtils.toByteArray(new GZIPInputStream(new ByteArrayInputStream(_jsonBytes)));
            } catch (IOException e) {
                _logger.error("Unable to unzip json data", e);
                throw new RuntimeException(e);
            }
        }
    }

    class JsonFormat implements MessageFormat {
        @Override
        public String getJSON() {
            return _json;
        }

        @Override
        public byte[] getJSONBytes() {
            return _jsonBytes;
        }
    }
}
