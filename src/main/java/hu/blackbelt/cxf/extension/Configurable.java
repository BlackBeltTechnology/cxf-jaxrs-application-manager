package hu.blackbelt.cxf.extension;

import java.util.Map;

/**
 * Configurable component.
 */
public interface Configurable {

    /**
     * Change configuration.
     *
     * @param config configuration options
     */
    void configure(Map<String, Object> config);
}
