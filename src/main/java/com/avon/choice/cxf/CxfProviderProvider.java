package com.avon.choice.cxf;

import com.avon.choice.cxf.providers.ISO8601DateParamHandler;
import com.avon.choice.cxf.providers.JacksonProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Provider of CXF providers used by MyAvon applications (i.e. Jackson, Date query parameter).
 */
@Slf4j
public class CxfProviderProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JacksonProvider jacksonProvider;
    private ISO8601DateParamHandler iso8601DateParamHandler = new ISO8601DateParamHandler();

    CxfProviderProvider() {
        objectMapper.registerModule(new JavaTimeModule());
        jacksonProvider = new JacksonProvider(objectMapper);
    }

    public List<Object> getProviders() {
        return Arrays.asList(jacksonProvider, iso8601DateParamHandler);
    }

    public void configure(final Map<String, Object> config) {
        jacksonProvider.configure(config);
        iso8601DateParamHandler.configure(config);
    }
}
