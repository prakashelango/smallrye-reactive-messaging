package io.smallrye.reactive.messaging.rabbitmq.internals;

import static io.smallrye.reactive.messaging.rabbitmq.i18n.RabbitMQExceptions.ex;
import static io.smallrye.reactive.messaging.rabbitmq.internals.RabbitMQClientHelper.getExchangeName;
import static java.time.Duration.ofSeconds;

import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.Subscriptions;
import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.reactive.messaging.OutgoingMessageMetadata;
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQConnectorOutgoingConfiguration;
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQMessageConverter;
import io.smallrye.reactive.messaging.rabbitmq.i18n.RabbitMQExceptions;
import io.smallrye.reactive.messaging.rabbitmq.i18n.RabbitMQLogging;
import io.smallrye.reactive.messaging.rabbitmq.tracing.RabbitMQOpenTelemetryInstrumenter;
import io.vertx.mutiny.rabbitmq.RabbitMQPublisher;

/**
 * An implementation of {@link Processor} and {@link Subscription} that is responsible for sending
 * RabbitMQ messages to an external broker.
 */
public class RabbitMQMessageSender implements Processor<Message<?>, Message<?>>, Subscription {

    private final Uni<RabbitMQPublisher> retrieveSender;
    private final RabbitMQConnectorOutgoingConfiguration configuration;

    private final AtomicReference<Subscription> upstream = new AtomicReference<>();
    private final AtomicReference<Subscriber<? super Message<?>>> downstream = new AtomicReference<>();
    private final String configuredExchange;
    private final boolean isTracingEnabled;

    private final long inflights;
    private final Optional<Long> defaultTtl;
    private final boolean publishConfirms;

    private final RabbitMQOpenTelemetryInstrumenter instrumenter;

    /**
     * Constructor.
     *
     * @param oc the configuration parameters for outgoing messages
     * @param retrieveSender the underlying Vert.x {@link RabbitMQPublisher}
     */
    public RabbitMQMessageSender(
            RabbitMQConnectorOutgoingConfiguration oc,
            Uni<RabbitMQPublisher> retrieveSender,
            Instance<OpenTelemetry> openTelemetryInstance) {
        this.retrieveSender = retrieveSender;
        this.configuration = oc;
        this.configuredExchange = getExchangeName(oc);
        this.isTracingEnabled = oc.getTracingEnabled();
        this.inflights = oc.getMaxInflightMessages();
        this.defaultTtl = oc.getDefaultTtl();
        this.publishConfirms = oc.getPublishConfirms();

        if (inflights <= 0) {
            throw ex.illegalArgumentInvalidMaxInflightMessages();
        }

        if (defaultTtl.isPresent() && defaultTtl.get() < 0) {
            throw ex.illegalArgumentInvalidDefaultTtl();
        }

        if (oc.getTracingEnabled()) {
            instrumenter = RabbitMQOpenTelemetryInstrumenter.createForSender(openTelemetryInstance);
        } else {
            instrumenter = null;
        }
    }

    /* ----------------------------------------------------- */
    /* METHODS OF PUBLISHER */
    /* ----------------------------------------------------- */

    /**
     * Request {@link Flow.Publisher} to start streaming data.
     * <p>
     * This is a "factory method" and can be called multiple times, each time starting a new {@link Subscription}.
     * <p>
     * Each {@link Subscription} will work for only a single {@link Subscriber}.
     * <p>
     * A {@link Subscriber} should only subscribe once to a single {@link Flow.Publisher}.
     * <p>
     * If the {@link Flow.Publisher} rejects the subscription attempt or otherwise fails it will
     * signal the error via {@link Subscriber#onError}.
     *
     * @param subscriber the {@link Subscriber} that will consume signals from this {@link Flow.Publisher}
     */
    @Override
    public void subscribe(
            final Subscriber<? super Message<?>> subscriber) {
        if (!downstream.compareAndSet(null, subscriber)) {
            Subscriptions.fail(subscriber, RabbitMQExceptions.ex.illegalStateOnlyOneSubscriberAllowed());
        } else {
            if (upstream.get() != null) {
                subscriber.onSubscribe(this);
            }
        }
    }

    /* ----------------------------------------------------- */
    /* METHODS OF SUBSCRIBER */
    /* ----------------------------------------------------- */

    /**
     * Invoked after calling {@link Flow.Publisher#subscribe(Subscriber)}.
     * <p>
     * No data will start flowing until {@link Subscription#request(long)} is invoked.
     * <p>
     * It is the responsibility of this {@link Subscriber} instance to call {@link Subscription#request(long)} whenever more
     * data is wanted.
     * <p>
     * The {@link Flow.Publisher} will send notifications only in response to {@link Subscription#request(long)}.
     *
     * @param subscription
     *        {@link Subscription} that allows requesting data via {@link Subscription#request(long)}
     */
    @Override
    public void onSubscribe(Subscription subscription) {
        if (this.upstream.compareAndSet(null, subscription)) {
            Subscriber<? super Message<?>> subscriber = downstream.get();
            if (subscriber != null) {
                subscriber.onSubscribe(this);
            }
        } else {
            Subscriber<? super Message<?>> subscriber = downstream.get();
            if (subscriber != null) {
                subscriber.onSubscribe(Subscriptions.CANCELLED);
            }
        }
    }

    /**
     * Data notification sent by the {@link Flow.Publisher} in response to requests to {@link Subscription#request(long)}.
     *
     * @param message the element signaled
     */
    @Override
    public void onNext(Message<?> message) {
        if (isCancelled()) {
            return;
        }

        final Subscriber<? super Message<?>> subscriber = this.downstream.get();

        retrieveSender
                .onItem().transformToUni(sender -> {
                    try {
                        return send(sender, message, configuredExchange, configuration)
                                .onItem().transform(m -> Tuple2.of(sender, m));
                    } catch (Exception e) {
                        // Message can't be sent - nacking and skipping.
                        RabbitMQLogging.log.serializationFailure(configuration.getChannel(), e);
                        return Uni.createFrom().completionStage(message.nack(e)).map(unused -> null);
                    }
                })
                .subscribe().with(
                        tuple -> {
                            if (tuple != null) {
                                subscriber.onNext(tuple.getItem2());

                                if (inflights != Long.MAX_VALUE) {
                                    upstream.get().request(1);
                                }
                            }
                        },
                        subscriber::onError);
    }

    /**
     * Failed terminal state.
     * <p>
     * No further events will be sent even if {@link Subscription#request(long)} is invoked again.
     *
     * @param t the throwable signaled
     */
    @Override
    public void onError(Throwable t) {
        Subscription sub = upstream.getAndSet(Subscriptions.CANCELLED);
        Subscriber<? super Message<?>> subscriber = this.downstream.get();
        if (sub != null && sub != Subscriptions.CANCELLED && subscriber != null) {
            subscriber.onError(t);
        }
    }

    /**
     * Successful terminal state.
     * <p>
     * No further events will be sent even if {@link Subscription#request(long)} is invoked again.
     */
    @Override
    public void onComplete() {
        Subscription sub = upstream.getAndSet(Subscriptions.CANCELLED);
        Subscriber<? super Message<?>> subscriber = this.downstream.get();
        if (sub != null && sub != Subscriptions.CANCELLED && subscriber != null) {
            subscriber.onComplete();
        }
    }

    /* ----------------------------------------------------- */
    /* METHODS OF SUBSCRIPTION */
    /* ----------------------------------------------------- */

    /**
     * No events will be sent by a {@link Flow.Publisher} until demand is signaled via this method.
     * <p>
     * It can be called however often and whenever needed—but if the outstanding cumulative demand ever becomes Long.MAX_VALUE
     * or more,
     * it may be treated by the {@link Flow.Publisher} as "effectively unbounded".
     * <p>
     * Whatever has been requested can be sent by the {@link Flow.Publisher} so only signal demand for what can be safely
     * handled.
     * <p>
     * A {@link Flow.Publisher} can send less than is requested if the stream ends but
     * then must emit either {@link Subscriber#onError(Throwable)} or {@link Subscriber#onComplete()}.
     * <p>
     * <strong>Note that this method is expected to be called only once on a given sender.</strong>
     * </p>
     *
     * @param l the strictly positive number of elements to requests to the upstream {@link Flow.Publisher}
     */
    @Override
    public void request(long l) {
        if (l != Long.MAX_VALUE) {
            throw ex.illegalStateConsumeWithoutBackPressure();
        }
        upstream.get().request(inflights);
    }

    /**
     * Request the {@link Flow.Publisher} to stop sending data and clean up resources.
     * <p>
     * Data may still be sent to meet previously signalled demand after calling cancel.
     */
    @Override
    public void cancel() {
        Subscription sub = upstream.getAndSet(Subscriptions.CANCELLED);
        if (sub != null && sub != Subscriptions.CANCELLED) {
            sub.cancel();
        }
    }

    /* ----------------------------------------------------- */
    /* HELPER METHODS */
    /* ----------------------------------------------------- */

    private Uni<Message<?>> send(
            final RabbitMQPublisher publisher,
            final Message<?> msg,
            final String exchange,
            final RabbitMQConnectorOutgoingConfiguration configuration) {
        final int retryAttempts = configuration.getRetryOnFailAttempts();
        final int retryInterval = configuration.getRetryOnFailInterval();
        final String defaultRoutingKey = configuration.getDefaultRoutingKey();

        final RabbitMQMessageConverter.OutgoingRabbitMQMessage outgoingRabbitMQMessage = RabbitMQMessageConverter
                .convert(instrumenter, msg, exchange, defaultRoutingKey, defaultTtl, isTracingEnabled);

        RabbitMQLogging.log.sendingMessageToExchange(exchange, outgoingRabbitMQMessage.getRoutingKey());
        Uni<Void> published;
        if (publishConfirms) {
            published = publisher.publishConfirm(exchange, outgoingRabbitMQMessage.getRoutingKey(),
                    outgoingRabbitMQMessage.getProperties(),
                    outgoingRabbitMQMessage.getBody())
                    .onItem().invoke(deliveryTag -> OutgoingMessageMetadata.setResultOnMessage(msg, deliveryTag))
                    .replaceWithVoid();
        } else {
            published = publisher.publish(exchange, outgoingRabbitMQMessage.getRoutingKey(),
                    outgoingRabbitMQMessage.getProperties(),
                    outgoingRabbitMQMessage.getBody());
        }
        return published
                .onFailure().retry().withBackOff(ofSeconds(1), ofSeconds(retryInterval)).atMost(retryAttempts)
                .onItemOrFailure().transformToUni((success, failure) -> {
                    if (failure != null) {
                        return Uni.createFrom().completionStage(msg.nack(failure));
                    } else {
                        return Uni.createFrom().completionStage(msg.ack());
                    }
                })
                .onItem().transform(x -> msg);
    }

    private boolean isCancelled() {
        final Subscription subscription = upstream.get();
        return subscription == Subscriptions.CANCELLED || subscription == null;
    }

}
