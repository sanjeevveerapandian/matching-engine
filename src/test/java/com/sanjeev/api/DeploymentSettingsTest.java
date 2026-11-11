package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

/** The settings a hosting platform relies on, read straight from application.properties. */
class DeploymentSettingsTest {

    private static Properties load() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    private static String resolved(String key, Map<String, String> environment) throws IOException {
        String raw = load().getProperty(key);
        assertNotNull(raw, key + " is not set");
        return new PropertyPlaceholderHelper("${", "}", ":", true).replacePlaceholders(raw, environment::get);
    }

    @Test
    void theServerListensOnThePortTheHostingPlatformGivesItInThePortVariable() throws IOException {
        assertEquals("10000", resolved("server.port", Map.of("PORT", "10000")));
    }

    @Test
    void withoutAPortVariableTheServerListensOn8080ForLocalUse() throws IOException {
        assertEquals("8080", resolved("server.port", Map.of()));
    }

    @Test
    void onlyTheHealthEndpointIsExposedAndItShowsNoDetails() throws IOException {
        Properties properties = load();

        assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", properties.getProperty("management.endpoint.health.show-details"));
    }
}
