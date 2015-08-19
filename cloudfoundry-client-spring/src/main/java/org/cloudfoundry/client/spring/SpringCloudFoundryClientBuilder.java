/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.client.spring;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import org.cloudfoundry.client.CloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.oauth2.client.DefaultOAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.token.DefaultAccessTokenRequest;
import org.springframework.util.Assert;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * A builder API for creating a Spring-backed implementation of the {@link CloudFoundryClient}.  By default it uses
 * {@code cf} and an empty string for the {@code clientId} and {@code clientSecret} respectively.
 *
 * <p><b>This class is NOT threadsafe.  The {@link CloudFoundryClient} created by it, is threadsafe.</b>
 */
public final class SpringCloudFoundryClientBuilder {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final RestOperations restOperations;

    private volatile String clientId = "cf";

    private volatile String clientSecret = "";

    private volatile String host;

    private volatile String username;

    private volatile String password;

    /**
     * Creates a new instance of the builder
     */
    public SpringCloudFoundryClientBuilder() {
        this(new RestTemplate());
    }

    SpringCloudFoundryClientBuilder(RestTemplate restTemplate) {
        this.restOperations = restTemplate;
    }

    /**
     * Configure the API endpoint to connect to
     *
     * @param host The host to connect to.  This is typically something like {@code api.run.pivotal.io}.
     * @return {@code this}
     */
    public SpringCloudFoundryClientBuilder withApi(String host) {
        this.host = host;
        return this;
    }

    /**
     * Configure the OAuth2 client information to use when connecting
     *
     * @param clientId     the client id to use. Defaults to {@code cf}.
     * @param clientSecret the client secret to use.  Defaults to {@code ''}.
     * @return {@code this}
     */
    public SpringCloudFoundryClientBuilder withClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        return this;
    }

    /**
     * Configure the credentials to use when connecting
     *
     * @param username the username to use
     * @param password the password to use
     * @return {@code this}
     */
    public SpringCloudFoundryClientBuilder withCredentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /**
     * Builds a new instance of a Spring-backed implementation of the {@link CloudFoundryClient} using the information
     * provided
     *
     * @return a new instance of a Spring-backed implementation of the {@link CloudFoundryClient}
     * @throws IllegalArgumentException if {@code host}, {@code username}, or {@code password} has not been set
     */
    public CloudFoundryClient build() {
        Assert.notNull(this.host, "host must be set");
        Assert.hasText(this.username, "username must be set");
        Assert.hasText(this.password, "password must be set");

        URI root = UriComponentsBuilder.newInstance().scheme("https").host(this.host).build().toUri();
        return new SpringCloudFoundryClient(getRestOperations(), root);
    }

    private OAuth2ClientContext getOAuth2ClientContext() {
        return new DefaultOAuth2ClientContext(new DefaultAccessTokenRequest());
    }

    private OAuth2ProtectedResourceDetails getOAuth2ProtectedResourceDetails() {
        return new ResourceOwnerPasswordResourceDetailsBuilder()
                .withClientId(this.clientId)
                .withClientSecret(this.clientSecret)
                .withAccessTokenUri(getAccessTokenUri())
                .withUsername(this.username)
                .withPassword(this.password)
                .build();
    }

    private RestTemplate getRestOperations() {
        OAuth2ProtectedResourceDetails oAuth2ProtectedResourceDetails = getOAuth2ProtectedResourceDetails();
        OAuth2ClientContext oAuth2ClientContext = getOAuth2ClientContext();

        RestTemplate restTemplate = new OAuth2RestTemplate(oAuth2ProtectedResourceDetails, oAuth2ClientContext);
        restTemplate.getMessageConverters().stream()
                .filter(converter -> converter instanceof MappingJackson2HttpMessageConverter)
                .map(converter -> (MappingJackson2HttpMessageConverter) converter)
                .findFirst()
                .ifPresent(converter -> {
                    this.logger.debug("Modifying ObjectMapper configuration");
                    converter.getObjectMapper().addHandler(new LoggingDeserializationProblemHandler());
                });

        return restTemplate;
    }

    @SuppressWarnings("unchecked")
    private String getAccessTokenUri() {
        String infoUri = UriComponentsBuilder.newInstance()
                .scheme("https").host(this.host).pathSegment("info")
                .build().toUriString();

        Map<String, String> results = this.restOperations.getForObject(infoUri, Map.class);

        return UriComponentsBuilder.fromUriString(results.get("token_endpoint"))
                .pathSegment("oauth", "token")
                .build().toUriString();
    }

    private static final class LoggingDeserializationProblemHandler extends DeserializationProblemHandler {

        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser jp,
                                             JsonDeserializer<?> deserializer, Object beanOrClass,
                                             String propertyName) throws IOException {
            this.logger.warn("Found unexpected property {} in payload for {}",
                    propertyName, beanOrClass.getClass().getSimpleName());
            return true;
        }

    }
}