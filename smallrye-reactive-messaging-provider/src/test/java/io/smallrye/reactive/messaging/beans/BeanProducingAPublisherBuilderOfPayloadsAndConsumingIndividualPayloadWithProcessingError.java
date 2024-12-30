package io.smallrye.reactive.messaging.beans;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;

import io.reactivex.Flowable;

@ApplicationScoped
public class BeanProducingAPublisherBuilderOfPayloadsAndConsumingIndividualPayloadWithProcessingError {

    @Incoming("count")
    @Outgoing("sink")
    @Acknowledgment(Acknowledgment.Strategy.POST_PROCESSING)
    public PublisherBuilder<String> process(Integer payload) {
        if (payload > 5) {
            throw new IllegalArgumentException("boom");
        }
        return ReactiveStreams.of(payload)
                .map(i -> i + 1)
                .flatMapRsPublisher(i -> Flowable.just(i, i))
                .map(i -> Integer.toString(i));
    }

}
