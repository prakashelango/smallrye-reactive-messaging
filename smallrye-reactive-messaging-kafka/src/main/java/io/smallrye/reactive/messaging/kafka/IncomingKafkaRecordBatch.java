package io.smallrye.reactive.messaging.kafka;

import static io.smallrye.reactive.messaging.providers.locals.ContextAwareMessage.captureContextMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordBatchMetadata;
import io.smallrye.reactive.messaging.kafka.commit.KafkaCommitHandler;
import io.smallrye.reactive.messaging.kafka.fault.KafkaFailureHandler;

public class IncomingKafkaRecordBatch<K, T> implements KafkaRecordBatch<K, T> {

    private final Metadata metadata;
    private final List<KafkaRecord<K, T>> incomingRecords;
    private final Map<TopicPartition, KafkaRecord<K, T>> latestOffsetRecords;

    public IncomingKafkaRecordBatch(ConsumerRecords<K, T> records, String channel, int index, KafkaCommitHandler commitHandler,
            KafkaFailureHandler onNack, boolean cloudEventEnabled, boolean tracingEnabled) {
        List<IncomingKafkaRecord<K, T>> incomingRecords = new ArrayList<>();
        Map<TopicPartition, IncomingKafkaRecord<K, T>> latestOffsetRecords = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            for (ConsumerRecord<K, T> record : records.records(partition)) {
                IncomingKafkaRecord<K, T> rec = new IncomingKafkaRecord<>(record, channel, index, commitHandler, onNack,
                        cloudEventEnabled, tracingEnabled);
                incomingRecords.add(rec);
                latestOffsetRecords.put(partition, rec);
            }
        }
        this.incomingRecords = Collections.unmodifiableList(incomingRecords);
        this.latestOffsetRecords = Collections.unmodifiableMap(latestOffsetRecords);
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        int generationId = -1;
        for (var entry : latestOffsetRecords.entrySet()) {
            generationId = entry.getValue().getConsumerGroupGenerationId();
            offsets.put(entry.getKey(), new OffsetAndMetadata(entry.getValue().getOffset()));
        }
        // This is safe because the IncomingKafkaRecord is Message
        List<Message<?>> batchedRecords = (List<Message<?>>) (List) this.incomingRecords;
        this.metadata = captureContextMetadata(
                new IncomingKafkaRecordBatchMetadata<>(records, batchedRecords, channel, index, offsets, generationId));
    }

    @Override
    public List<T> getPayload() {
        return this.incomingRecords.stream().map(KafkaRecord::getPayload).collect(Collectors.toList());
    }

    @Override
    public List<KafkaRecord<K, T>> getRecords() {
        return this.incomingRecords;
    }

    @Override
    public Iterator<KafkaRecord<K, T>> iterator() {
        return this.getRecords().iterator();
    }

    @Override
    public Map<TopicPartition, KafkaRecord<K, T>> getLatestOffsetRecords() {
        return this.latestOffsetRecords;
    }

    @Override
    public Metadata getMetadata() {
        return metadata;
    }

    @Override
    public Function<Metadata, CompletionStage<Void>> getAckWithMetadata() {
        return this::ack;
    }

    @Override
    public BiFunction<Throwable, Metadata, CompletionStage<Void>> getNackWithMetadata() {
        return this::nack;
    }

    @Override
    public CompletionStage<Void> ack(Metadata metadata) {
        return Multi.createBy().concatenating().collectFailures()
                .streams(this.latestOffsetRecords.values().stream()
                        .map(record -> Multi.createFrom().completionStage(record.ack(metadata)))
                        .collect(Collectors.toList()))
                .toUni().subscribeAsCompletionStage();
    }

    @Override
    public CompletionStage<Void> nack(Throwable reason, Metadata metadata) {
        return Multi.createBy().concatenating().collectFailures()
                .streams(this.incomingRecords.stream()
                        .map(record -> Multi.createFrom().completionStage(() -> record.nack(reason, metadata)))
                        .collect(Collectors.toList()))
                .toUni().subscribeAsCompletionStage();
    }
}
