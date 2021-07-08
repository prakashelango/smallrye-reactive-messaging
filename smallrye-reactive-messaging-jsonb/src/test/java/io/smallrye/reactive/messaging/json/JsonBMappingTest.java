package io.smallrye.reactive.messaging.json;

import io.smallrye.reactive.messaging.support.JmsTestBase;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

public class JsonBMappingTest extends JmsTestBase {

    @Test
    @DisplayName("Test the conversion from string to object and back")
    void identityString() {
        MapBasedConfig config = new MapBasedConfig(Collections.emptyMap());
        addConfig(config);
        WeldContainer container = deploy();

        JsonBMapping mapping = container.select(JsonBMapping.class).get();
        final String testObjectAsJson = "{\"my_id\": 1, \"my_Payload\": \"Lorem ipsum\"}";
        assertThat(mapping.toJson(mapping.fromJson(testObjectAsJson, TestObject.class))).isNotNull();
    }
}
