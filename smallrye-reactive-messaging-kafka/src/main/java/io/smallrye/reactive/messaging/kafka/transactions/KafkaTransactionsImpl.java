package io.smallrye.reactive.messaging.kafka.transactions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TransactionAbortedException;
import org.eclipse.microprofile.reactive.messaging.Message;

import io.smallrye.common.annotation.CheckReturnValue;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.EmitterConfiguration;
import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import io.smallrye.reactive.messaging.kafka.KafkaConsumer;
import io.smallrye.reactive.messaging.kafka.KafkaProducer;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordBatchMetadata;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.kafka.i18n.KafkaExceptions;
import io.smallrye.reactive.messaging.kafka.i18n.KafkaLogging;
import io.smallrye.reactive.messaging.kafka.impl.TopicPartitions;
import io.smallrye.reactive.messaging.providers.extension.MutinyEmitterImpl;
import io.smallrye.reactive.messaging.providers.helpers.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

public class KafkaTransactionsImpl<T> extends MutinyEmitterImpl<T> implements KafkaTransactions<T> {

    private final KafkaClientService clientService;
    private final KafkaProducer<?, ?> producer;

    private volatile Transaction<?> currentTransaction;

    private final ReentrantLock lock = new ReentrantLock();

    public KafkaTransactionsImpl(EmitterConfiguration config, long defaultBufferSize, KafkaClientService clientService) {
        super(config, defaultBufferSize);
        this.clientService = clientService;
        this.producer = clientService.getProducer(config.name());
    }

    @Override
    public boolean isTransactionInProgress() {
        lock.lock();
        try {
            return currentTransaction != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @CheckReturnValue
    public <R> Uni<R> withTransaction(Function<TransactionalEmitter<T>, Uni<R>> work) {
        lock.lock();
        try {
            if (currentTransaction == null) {
                return new Transaction<R>().execute(work);
            }
            throw KafkaExceptions.ex.transactionInProgress(name);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    @CheckReturnValue
    public <R> Uni<R> withTransaction(Message<?> message, Function<TransactionalEmitter<T>, Uni<R>> work) {
        lock.lock();
        try {
            String channel;
            Map<TopicPartition, OffsetAndMetadata> offsets;
            int generationId;

            Optional<IncomingKafkaRecordBatchMetadata> batchMetadata = message
                    .getMetadata(IncomingKafkaRecordBatchMetadata.class);
            Optional<IncomingKafkaRecordMetadata> recordMetadata = message.getMetadata(IncomingKafkaRecordMetadata.class);
            if (batchMetadata.isPresent()) {
                IncomingKafkaRecordBatchMetadata<?, ?> metadata = batchMetadata.get();
                channel = metadata.getChannel();
                generationId = metadata.getConsumerGroupGenerationId();
                offsets = metadata.getOffsets().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue().offset() + 1)));
            } else if (recordMetadata.isPresent()) {
                IncomingKafkaRecordMetadata<?, ?> metadata = recordMetadata.get();
                channel = metadata.getChannel();
                offsets = new HashMap<>();
                generationId = metadata.getConsumerGroupGenerationId();
                offsets.put(TopicPartitions.getTopicPartition(metadata.getTopic(), metadata.getPartition()),
                        new OffsetAndMetadata(metadata.getOffset() + 1));
            } else {
                throw KafkaExceptions.ex.noKafkaMetadataFound(message);
            }
            List<KafkaConsumer<Object, Object>> consumers = clientService.getConsumers(channel);
            if (consumers.isEmpty()) {
                throw KafkaExceptions.ex.unableToFindConsumerForChannel(channel);
            } else if (consumers.size() > 1) {
                throw KafkaExceptions.ex.exactlyOnceProcessingNotSupported(channel);
            }
            KafkaConsumer<Object, Object> consumer = consumers.get(0);
            if (currentTransaction == null) {
                return new Transaction<R>(
                        /* before commit */
                        consumer.consumerGroupMetadata().chain(groupMetadata -> {
                            // if the generationId is the same, we can send the offsets to tx
                            if (groupMetadata.generationId() == generationId) {
                                // stay on the polling thread
                                producer.unwrap().sendOffsetsToTransaction(offsets, groupMetadata);
                                return Uni.createFrom().voidItem();
                            } else {
                                // abort the transaction if the generationId is different,
                                // after abort will set the consumer position to the last committed positions
                                return Uni.createFrom().failure(
                                        KafkaExceptions.ex.exactlyOnceProcessingRebalance(channel, groupMetadata.toString(),
                                                String.valueOf(generationId)));
                            }
                        }),
                        r -> Uni.createFrom().item(r),
                        VOID_UNI,
                        /* after abort */
                        t -> consumer.resetToLastCommittedPositions()
                                .chain(() -> Uni.createFrom().failure(t)))
                        .execute(work);
            }
            throw KafkaExceptions.ex.transactionInProgress(name);
        } finally {
            lock.unlock();
        }
    }

    private static final Uni<Void> VOID_UNI = Uni.createFrom().voidItem();

    private static <R> Uni<R> defaultAfterCommit(R result) {
        return Uni.createFrom().item(result);
    }

    private static <R> Uni<R> defaultAfterAbort(Throwable throwable) {
        return Uni.createFrom().failure(throwable);
    }

    private class Transaction<R> implements TransactionalEmitter<T> {

        private final Uni<Void> beforeCommit;
        private final Function<R, Uni<R>> afterCommit;

        private final Uni<Void> beforeAbort;
        private final Function<Throwable, Uni<R>> afterAbort;

        private final List<Uni<Void>> sendUnis = new CopyOnWriteArrayList<>();
        private volatile boolean abort;

        public Transaction() {
            this(VOID_UNI, KafkaTransactionsImpl::defaultAfterCommit, VOID_UNI, KafkaTransactionsImpl::defaultAfterAbort);
        }

        public Transaction(Uni<Void> beforeCommit, Function<R, Uni<R>> afterCommit,
                Uni<Void> beforeAbort, Function<Throwable, Uni<R>> afterAbort) {
            this.beforeCommit = beforeCommit;
            this.afterCommit = afterCommit;
            this.beforeAbort = beforeAbort;
            this.afterAbort = afterAbort;
        }

        Uni<R> execute(Function<TransactionalEmitter<T>, Uni<R>> work) {
            currentTransaction = this;
            final ContextExecutor executor = new ContextExecutor();
            return producer.beginTransaction()
                    .plug(executor::emitOn)
                    .chain(() -> executeInTransaction(work))
                    .eventually(() -> currentTransaction = null)
                    .plug(executor::emitOn);
        }

        private Uni<R> executeInTransaction(Function<TransactionalEmitter<T>, Uni<R>> work) {
            //noinspection Convert2MethodRef
            return Uni.createFrom().nullItem()
                    .chain(() -> work.apply(this))
                    // wait until all send operations are completed
                    .eventually(() -> waitOnSend())
                    // only flush() if the work completed with no exception
                    .call(() -> producer.flush())
                    // in the case of an exception or cancellation
                    // we need to rollback the transaction
                    .onFailure().call(throwable -> abort())
                    .onCancellation().call(() -> abort())
                    // when there was no exception,
                    // commit or rollback the transaction
                    .call(() -> abort ? abort() : commit().onFailure().recoverWithUni(throwable -> {
                        KafkaLogging.log.transactionCommitFailed(throwable);
                        return abort();
                    }))
                    // finally, call after commit or after abort callbacks
                    .onFailure().recoverWithUni(throwable -> afterAbort.apply(throwable))
                    .onItem().transformToUni(result -> afterCommit.apply(result));
        }

        private Uni<List<Void>> waitOnSend() {
            return sendUnis.isEmpty() ? Uni.createFrom().nullItem() : Uni.join().all(sendUnis).andCollectFailures();
        }

        private Uni<Void> commit() {
            return beforeCommit.call(producer::commitTransaction);
        }

        private Uni<Void> abort() {
            Uni<Void> uni = beforeAbort.call(producer::abortTransaction);
            return abort ? uni.chain(() -> Uni.createFrom().failure(new TransactionAbortedException())) : uni;
        }

        @Override
        public <M extends Message<? extends T>> void send(M msg) {
            CompletableFuture<Void> send = KafkaTransactionsImpl.this.sendMessage(msg)
                    .onFailure().invoke(KafkaLogging.log::unableToSendRecord)
                    .subscribeAsCompletionStage();
            sendUnis.add(Uni.createFrom().completionStage(send));
        }

        @Override
        public void send(T payload) {
            CompletableFuture<Void> send = KafkaTransactionsImpl.this.send(payload)
                    .onFailure().invoke(KafkaLogging.log::unableToSendRecord)
                    .subscribeAsCompletionStage();
            sendUnis.add(Uni.createFrom().completionStage(send));
        }

        @Override
        public void markForAbort() {
            abort = true;
        }

        @Override
        public boolean isMarkedForAbort() {
            return abort;
        }
    }

    /**
     * An executor that captures the caller Vert.x context and whether the caller is on an event loop thread or worker thread.
     * Runs the command on the Vert.x event loop thread if the current thread is an event loop thread.
     * <p>
     * And if run on worker thread, `work` is called on the worker thread.
     */
    private static class ContextExecutor implements Executor {
        private final Context context;
        private final boolean ioThread;

        ContextExecutor() {
            this(Vertx.currentContext(), Context.isOnEventLoopThread());
        }

        ContextExecutor(Context context, boolean ioThread) {
            this.context = context;
            this.ioThread = ioThread;
        }

        <T> Uni<T> emitOn(Uni<T> uni) {
            return context == null ? uni : uni.emitOn(this);
        }

        @Override
        public void execute(Runnable command) {
            if (context == null) {
                command.run();
            } else {
                if (ioThread) {
                    VertxContext.runOnContext(context, command);
                } else {
                    VertxContext.executeBlocking(context, command);
                }
            }
        }
    }

}
