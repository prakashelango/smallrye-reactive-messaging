package io.smallrye.reactive.messaging.amqp.ssl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.config.SmallRyeConfigProviderResolver;
import io.smallrye.reactive.messaging.amqp.AmqpBrokerHolder;
import io.smallrye.reactive.messaging.amqp.AmqpConnector;
import io.smallrye.reactive.messaging.amqp.AmqpUsage;
import io.smallrye.reactive.messaging.amqp.ProducingBean;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class AmqpSSLSinkCDISSLContextTest extends AmqpBrokerHolder {

    private WeldContainer container;

    @BeforeAll
    public static void startBroker() throws IOException, URISyntaxException {
        startBroker(SSLBrokerConfigUtil.createSecuredBrokerXml());
    }

    @AfterAll
    public static void stopBroker() {
        AmqpBrokerHolder.stopBroker();
    }

    @Override
    @BeforeEach
    public void setup() {
        super.setup();
        // Override the usage port
        usage.close();
        usage = new AmqpUsage(executionHolder.vertx(), host, port + 1, username, password);
    }

    @Override
    @AfterEach
    public void tearDown() {
        super.tearDown();
    }

    @AfterEach
    public void cleanup() {
        if (container != null) {
            container.shutdown();
        }

        MapBasedConfig.cleanup();
        SmallRyeConfigProviderResolver.instance().releaseConfig(ConfigProvider.getConfig());

        System.clearProperty("mp-config");
        System.clearProperty("client-options-name");
        System.clearProperty("amqp-client-options-name");
    }

    @Test
    public void testSuppliedSslContextGlobal() throws InterruptedException {
        Weld weld = new Weld();

        CountDownLatch latch = new CountDownLatch(10);
        usage.consumeIntegers("sink",
                v -> latch.countDown());

        weld.addBeanClass(ProducingBean.class);
        weld.addBeanClass(ClientSslContextBean.class);

        new MapBasedConfig()
                .put("mp.messaging.outgoing.sink.address", "sink")
                .put("mp.messaging.outgoing.sink.connector", AmqpConnector.CONNECTOR_NAME)
                .put("mp.messaging.outgoing.sink.host", host)
                .put("mp.messaging.outgoing.sink.port", port)
                .put("mp.messaging.outgoing.sink.durable", false)
                .put("mp.messaging.outgoing.sink.tracing-enabled", false)
                .put("amqp-username", username)
                .put("amqp-password", password)
                .put("amqp-use-ssl", "true")
                .put("amqp-client-ssl-context-name", "mysslcontext")
                .write();

        container = weld.initialize();

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
    }

    @Test
    public void testSuppliedSslContextConnector() throws InterruptedException {
        Weld weld = new Weld();

        CountDownLatch latch = new CountDownLatch(10);
        usage.consumeIntegers("sink",
                v -> latch.countDown());

        weld.addBeanClass(ProducingBean.class);
        weld.addBeanClass(ClientSslContextBean.class);

        new MapBasedConfig()
                .put("mp.messaging.outgoing.sink.address", "sink")
                .put("mp.messaging.outgoing.sink.connector", AmqpConnector.CONNECTOR_NAME)
                .put("mp.messaging.outgoing.sink.host", host)
                .put("mp.messaging.outgoing.sink.port", port)
                .put("mp.messaging.outgoing.sink.durable", false)
                .put("mp.messaging.outgoing.sink.tracing-enabled", false)
                .put("mp.messaging.outgoing.sink.username", username)
                .put("mp.messaging.outgoing.sink.password", password)
                .put("mp.messaging.outgoing.sink.use-ssl", "true")
                .put("mp.messaging.outgoing.sink.client-ssl-context-name", "mysslcontext")
                .write();

        container = weld.initialize();

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
    }
}
