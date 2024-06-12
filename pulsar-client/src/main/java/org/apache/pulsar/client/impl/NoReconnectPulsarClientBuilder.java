package org.apache.pulsar.client.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;

public class NoReconnectPulsarClientBuilder extends ClientBuilderImpl {

    public NoReconnectPulsarClientBuilder() {
        this(new ClientConfigurationData());
    }

    public NoReconnectPulsarClientBuilder(ClientConfigurationData conf) {
        this.conf = conf;
    }

    public static NoReconnectPulsarClientBuilder noReconnectPulsarClientBuilder() {
        return new NoReconnectPulsarClientBuilder();
    }

    @Override
    public PulsarClient build() throws PulsarClientException {
        // NOTE: the method's implementation is the same as in superclass,
        // except client object that is being built is of class NoReconnectPulsarClientImpl

        if (StringUtils.isBlank(conf.getServiceUrl()) && conf.getServiceUrlProvider() == null) {
            throw new IllegalArgumentException("service URL or service URL provider needs to be specified on the ClientBuilder object.");
        }
        if (StringUtils.isNotBlank(conf.getServiceUrl()) && conf.getServiceUrlProvider() != null) {
            throw new IllegalArgumentException("Can only choose one way service URL or service URL provider.");
        }
        if (conf.getServiceUrlProvider() != null) {
            if (StringUtils.isBlank(conf.getServiceUrlProvider().getServiceUrl())) {
                throw new IllegalArgumentException("Cannot get service url from service url provider.");
            } else {
                conf.setServiceUrl(conf.getServiceUrlProvider().getServiceUrl());
            }
        }
        PulsarClient client = new NoReconnectPulsarClientImpl(conf);
        if (conf.getServiceUrlProvider() != null) {
            conf.getServiceUrlProvider().initialize(client);
        }
        return client;
    }

}

