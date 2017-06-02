/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.spi.persistence;

import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import io.moquette.spi.IMatchingCondition;
import io.moquette.spi.IMessagesStore;
import org.mapdb.DB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static io.moquette.spi.persistence.MapDBSessionsStore.messageId2GuidsMapName;

/**
 * IMessagesStore implementation backed by MapDB.
 *
 * @author andrea
 */
class MapDBMessagesStore implements IMessagesStore {

    private static final Logger LOG = LoggerFactory.getLogger(MapDBMessagesStore.class);

    private DB m_db;

    //maps clientID -> guid
    private ConcurrentMap<String, String> m_retainedStore;
    //maps guid to message, it's message store
    private ConcurrentMap<String, IMessagesStore.StoredMessage> m_persistentMessageStore;


    MapDBMessagesStore(DB db) {
        m_db = db;
    }

    @Override
    public void initStore() {
        m_retainedStore = m_db.getHashMap("retained");
        m_persistentMessageStore = m_db.getHashMap("persistedMessages");
    }

    @Override
    public void storeRetained(String topic, String guid) {
        m_retainedStore.put(topic, guid);
    }

    @Override
    public Collection<StoredMessage> searchMatching(IMatchingCondition condition) {
        LOG.debug("searchMatching scanning all retained messages, presents are {}", m_retainedStore.size());

        List<StoredMessage> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : m_retainedStore.entrySet()) {
            final String guid = entry.getValue();
            if (condition.match(entry.getKey())) {
                StoredMessage storedMsg = m_persistentMessageStore.get(guid);
                results.add(storedMsg);
            }
        }

        return results;
    }

    @Override
    public String storePublishForFuture(StoredMessage evt) {
        LOG.debug("storePublishForFuture store evt {}", evt);
        if (evt.getClientID() == null) {
            LOG.error("persisting a message without a clientID, bad programming error msg: {}", evt);
            throw new IllegalArgumentException("\"persisting a message without a clientID, bad programming error");
        }
        String guid = UUID.randomUUID().toString();
        evt.setGuid(guid);
        m_persistentMessageStore.put(guid, evt);
        ConcurrentMap<Integer, String> messageIdToGuid = m_db.getHashMap(messageId2GuidsMapName(evt.getClientID()));
        messageIdToGuid.put(evt.getMessageID(), guid);
        return guid;
    }

    @Override
    public void dropInFlightMessagesInSession(Collection<String> pendingAckMessages) {
        //remove all guids from retained
        Collection<String> messagesToRemove = new HashSet<>(pendingAckMessages);
        messagesToRemove.removeAll(m_retainedStore.values());

        for (String guid : messagesToRemove) {
            m_persistentMessageStore.remove(guid);
        }
    }

    @Override
    public List<StoredMessage> listMessagesInSession(Collection<String> guids) {
        List<StoredMessage> ret = new ArrayList<>();
        for (String guid : guids) {
            ret.add(m_persistentMessageStore.get(guid));
        }
        return ret;
    }


    @Override
    public StoredMessage getMessageByGuid(String guid) {
        return m_persistentMessageStore.get(guid);
    }

    @Override
    public void cleanRetained(String topic) {
        m_retainedStore.remove(topic);
    }


    public void dropMessagesNotIn(Collection<String> guids) {
        for (String toRemove : FluentIterable.from(ImmutableSet.copyOf(m_persistentMessageStore.keySet()))
                .filter(not(in(guids)))
                .filter(not(in(m_retainedStore.values())))
                ) {
            m_persistentMessageStore.remove(toRemove);
        }
    }
}
