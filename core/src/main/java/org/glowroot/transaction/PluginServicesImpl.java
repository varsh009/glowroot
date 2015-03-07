/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.transaction;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.transaction.model.TimerExt;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.TimerNameImpl;
import org.glowroot.transaction.model.Transaction;

import static com.google.common.base.Preconditions.checkNotNull;

class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final ConfigService configService;
    private final TimerNameCache timerNameCache;
    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;
    private final UserProfileScheduler userProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;

    // pluginId is either the id of a registered plugin or it is null
    // (see validation in constructor)
    private final @Nullable String pluginId;

    // cache for fast read access
    private volatile boolean enabled;
    private volatile boolean captureThreadInfo;
    private volatile boolean captureGcInfo;
    private volatile int maxTraceEntriesPerTransaction;
    private volatile @MonotonicNonNull PluginConfig pluginConfig;

    static PluginServicesImpl create(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        PluginServicesImpl pluginServices = new PluginServicesImpl(transactionRegistry,
                transactionCollector, configService, timerNameCache, threadAllocatedBytes,
                userProfileScheduler, ticker, clock, pluginDescriptors, pluginId);
        // add config listeners first before caching configuration property values to avoid a
        // (remotely) possible race condition
        configService.addConfigListener(pluginServices);
        if (pluginId != null) {
            configService.addPluginConfigListener(pluginId, pluginServices);
        }
        // call onChange() to initialize the cached configuration property values
        pluginServices.onChange();
        return pluginServices;
    }

    private PluginServicesImpl(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.configService = configService;
        this.timerNameCache = timerNameCache;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.userProfileScheduler = userProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
        if (pluginId == null) {
            this.pluginId = null;
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                List<String> ids = Lists.newArrayList();
                for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                    ids.add(pluginDescriptor.id());
                }
                logger.warn("unexpected plugin id: {} (available plugin ids are {})", pluginId,
                        Joiner.on(", ").join(ids));
                this.pluginId = null;
            } else {
                this.pluginId = pluginId;
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getStringProperty(String name) {
        if (name == null) {
            logger.error("getStringProperty(): argument 'name' must be non-null");
            return "";
        }
        if (pluginConfig == null) {
            return "";
        }
        return pluginConfig.getStringProperty(name);
    }

    @Override
    public boolean getBooleanProperty(String name) {
        if (name == null) {
            logger.error("getBooleanProperty(): argument 'name' must be non-null");
            return false;
        }
        return pluginConfig != null && pluginConfig.getBooleanProperty(name);
    }

    @Override
    public @Nullable Double getDoubleProperty(String name) {
        if (name == null) {
            logger.error("getDoubleProperty(): argument 'name' must be non-null");
            return null;
        }
        if (pluginConfig == null) {
            return null;
        }
        return pluginConfig.getDoubleProperty(name);
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        if (pluginId == null) {
            return;
        }
        if (listener == null) {
            logger.error("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
    }

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return timerNameCache.getName(adviceClass);
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        return startTransactionInternal(transactionType, transactionName, messageSupplier,
                timerName);
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (messageSupplier == null) {
            logger.error("startTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTraceEntry(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTraceEntry.INSTANCE;
        }
        return startTraceEntryInternal(transaction, timerName, messageSupplier);
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        if (timerName == null) {
            logger.error("startTimer(): argument 'timerName' must be non-null");
            return NopTimer.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTimer.INSTANCE;
        }
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            return NopTimer.INSTANCE;
        }
        return currentTimer.startNestedTimer(timerName);
    }

    @Override
    public void addTraceEntry(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("addTraceEntry(): argument 'errorMessage' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction != null
                && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
            long currTick = ticker.read();
            org.glowroot.transaction.model.TraceEntry entry =
                    transaction.addEntry(currTick, currTick, null, errorMessage, true);
            if (((ReadableErrorMessage) errorMessage).getExceptionInfo() == null) {
                entry.setStackTrace(PluginServicesImpl.captureStackTrace());
            }
        }
    }

    @Override
    public void setTransactionType(@Nullable String transactionType) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionType(transactionType);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionName(transactionName);
        }
    }

    @Override
    public void setTransactionError(@Nullable String error) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setError(error);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null && !Strings.isNullOrEmpty(user)) {
            transaction.setUser(user);
            if (transaction.getUserProfileRunnable() == null) {
                userProfileScheduler.maybeScheduleUserProfiling(transaction, user);
            }
        }
    }

    @Override
    public void setTransactionCustomAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("setTransactionCustomAttribute(): argument 'name' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.putCustomAttribute(name, value);
        }
    }

    @Override
    public void setTraceStoreThreshold(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("setTraceStoreThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        if (unit == null) {
            logger.error("setTraceStoreThreshold(): argument 'unit' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setTraceStoreThresholdMillisOverride(thresholdMillis);
        }
    }

    @Override
    public boolean isInTransaction() {
        return transactionRegistry.getCurrentTransaction() != null;
    }

    @Override
    public void onChange() {
        GeneralConfig generalConfig = configService.getGeneralConfig();
        if (pluginId == null) {
            enabled = generalConfig.enabled();
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            // pluginConfig should not be null since pluginId was already validated
            // at construction time and plugins cannot be removed (or their ids changed) at runtime
            checkNotNull(pluginConfig);
            enabled = generalConfig.enabled() && pluginConfig.enabled();
            this.pluginConfig = pluginConfig;
        }
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        maxTraceEntriesPerTransaction = advancedConfig.maxTraceEntriesPerTransaction();
        captureThreadInfo = advancedConfig.captureThreadInfo();
        captureGcInfo = advancedConfig.captureGcInfo();
    }

    private TraceEntry startTransactionInternal(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            TimerImpl rootTimer = TimerImpl.createRootTimer((TimerNameImpl) timerName, ticker);
            long startTick = ticker.read();
            rootTimer.start(startTick);
            transaction = new Transaction(clock.currentTimeMillis(), transactionType,
                    transactionName, messageSupplier, rootTimer, startTick, captureThreadInfo,
                    captureGcInfo, threadAllocatedBytes, ticker);
            transactionRegistry.addTransaction(transaction);
            return new TraceEntryImpl(transaction.getTraceEntryComponent(), transaction);
        } else {
            return startTraceEntryInternal(transaction, timerName, messageSupplier);
        }
    }

    private TraceEntry startTraceEntryInternal(Transaction transaction, TimerName timerName,
            MessageSupplier messageSupplier) {
        long startTick = ticker.read();
        if (transaction.getEntryCount() >= maxTraceEntriesPerTransaction) {
            // the entry limit has been exceeded for this trace
            transaction.addEntryLimitExceededMarkerIfNeeded();
            TimerExt timer = startTimer(timerName, startTick, transaction);
            return new DummyTraceEntry(timer, startTick, transaction, messageSupplier);
        } else {
            TimerExt timer = startTimer(timerName, startTick, transaction);
            org.glowroot.transaction.model.TraceEntry traceEntry =
                    transaction.pushEntry(startTick, messageSupplier, timer);
            return new TraceEntryImpl(traceEntry, transaction);
        }
    }

    private TimerExt startTimer(TimerName timerName, long startTick, Transaction transaction) {
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            // this really shouldn't happen as current timer should be non-null unless transaction
            // has completed
            return NopTimerExt.INSTANCE;
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    private static ImmutableList<StackTraceElement> captureStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // need to strip back a few stack calls:
        // skip i=0 which is "java.lang.Thread.getStackTrace()"
        for (int i = 1; i < stackTrace.length; i++) {
            // startsWith to include nested classes
            if (!stackTrace[i].getClassName().startsWith(PluginServicesImpl.class.getName())) {
                // found the caller of PluginServicesImpl, this should be the @Pointcut
                // @OnReturn/@OnThrow/@OnAfter method, next one should be the woven method
                return ImmutableList.copyOf(stackTrace).subList(i + 1, stackTrace.length);
            }
        }
        logger.warn("stack trace didn't include endWithStackTrace()");
        return ImmutableList.of();
    }

    private class TraceEntryImpl implements TraceEntry {
        private final org.glowroot.transaction.model.TraceEntry traceEntry;
        private final Transaction transaction;
        private TraceEntryImpl(org.glowroot.transaction.model.TraceEntry traceEntry,
                Transaction transaction) {
            this.traceEntry = traceEntry;
            this.transaction = transaction;
        }
        @Override
        public void end() {
            endInternal(ticker.read(), null);
        }
        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
                end();
                return;
            }
            long endTick = ticker.read();
            if (endTick - traceEntry.getStartTick() >= unit.toNanos(threshold)) {
                traceEntry.setStackTrace(captureStackTrace());
            }
            endInternal(endTick, null);
        }
        @Override
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            endInternal(ticker.read(), errorMessage);
        }
        @Override
        public MessageSupplier getMessageSupplier() {
            MessageSupplier messageSupplier = traceEntry.getMessageSupplier();
            // messageSupplier should never be null since entry.getMessageSupplier() is only null
            // when the entry was created using addErrorEntry(), and that method doesn't return the
            // entry afterwards, so it should be impossible to call getMessageSupplier() on it
            checkNotNull(messageSupplier);
            return messageSupplier;
        }
        private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
            transaction.popEntry(traceEntry, endTick, errorMessage);
            if (transaction.isCompleted()) {
                // the root entry has been popped off
                safeCancel(transaction.getImmedateTraceStoreRunnable());
                safeCancel(transaction.getUserProfileRunnable());
                // send to trace collector before removing from trace registry so that trace
                // collector can cover the gap (via
                // TransactionCollectorImpl.getPendingCompleteTraces())
                // between removing the trace from the registry and storing it
                transactionCollector.onCompletedTransaction(transaction);
                transactionRegistry.removeTransaction(transaction);
            }
        }
        private void safeCancel(@Nullable ScheduledRunnable scheduledRunnable) {
            if (scheduledRunnable == null) {
                return;
            }
            scheduledRunnable.cancel();
        }
    }

    private class DummyTraceEntry implements TraceEntry {
        private final TimerExt timer;
        private final long startTick;
        private final Transaction transaction;
        private final MessageSupplier messageSupplier;
        public DummyTraceEntry(TimerExt timer, long startTick, Transaction transaction,
                MessageSupplier messageSupplier) {
            this.timer = timer;
            this.startTick = startTick;
            this.transaction = transaction;
            this.messageSupplier = messageSupplier;
        }
        @Override
        public void end() {
            timer.stop();
        }
        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
                end();
                return;
            }
            long endTick = ticker.read();
            timer.end(endTick);
            // use higher entry limit when adding slow entries, but still need some kind of cap
            if (endTick - startTick >= unit.toNanos(threshold)
                    && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't necessarily be nested properly, and won't have any timing data, but
                // at least the long entry and stack trace will get captured
                org.glowroot.transaction.model.TraceEntry entry =
                        transaction.addEntry(startTick, endTick, messageSupplier, null, true);
                entry.setStackTrace(captureStackTrace());
            }
        }
        @Override
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            long endTick = ticker.read();
            timer.end(endTick);
            // use higher entry limit when adding errors, but still need some kind of cap
            if (transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't be nested properly, but at least the error will get captured
                transaction.addEntry(startTick, endTick, messageSupplier, errorMessage, true);
            }
        }
        @Override
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    private static class NopTraceEntry implements TraceEntry {
        private static final NopTraceEntry INSTANCE = new NopTraceEntry();
        private NopTraceEntry() {}
        @Override
        public void end() {}
        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {}
        @Override
        public void endWithError(ErrorMessage errorMessage) {}
        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    private static class NopTimer implements Timer {
        private static final NopTimer INSTANCE = new NopTimer();
        @Override
        public void stop() {}
    }

    private static class NopTimerExt implements TimerExt {
        private static final NopTimerExt INSTANCE = new NopTimerExt();
        @Override
        public void stop() {}
        @Override
        public void end(long endTick) {}
    }
}
