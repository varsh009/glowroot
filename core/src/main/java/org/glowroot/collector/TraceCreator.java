/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.io.CharStreams;

import org.glowroot.common.Marshaling2;
import org.glowroot.transaction.model.GcInfoComponent.GcInfo;
import org.glowroot.transaction.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.Transaction;

public class TraceCreator {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private TraceCreator() {}

    public static Trace createActiveTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createTrace(transaction, true, false, captureTime, captureTick);
    }

    public static Trace createPartialTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        // doesn't really matter whether pass true or false for "active" since this method is only
        // used for creating trace for storage and stored traces do not have "active" field
        return createTrace(transaction, true, true, captureTime, captureTick);
    }

    public static Trace createCompletedTrace(Transaction transaction) throws IOException {
        return createTrace(transaction, false, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Trace createTrace(Transaction transaction, boolean active, boolean partial,
            long captureTime, long captureTick) throws IOException {
        ImmutableTrace.Builder builder = ImmutableTrace.builder();
        builder.id(transaction.getId());
        builder.active(active);
        builder.partial(partial);
        builder.startTime(transaction.getStartTime());
        builder.captureTime(captureTime);
        builder.duration(captureTick - transaction.getStartTick());
        builder.transactionType(transaction.getTransactionType());
        builder.transactionName(transaction.getTransactionName());
        builder.headline(transaction.getHeadline());
        builder.error(transaction.getError());
        builder.user(transaction.getUser());
        builder.customAttributes(writeCustomAttributesAsString(transaction.getCustomAttributes()));
        builder.customAttributesForIndexing(transaction.getCustomAttributes());
        builder.customDetail(writeCustomDetailAsString(transaction.getCustomDetail()));
        builder.timers(writeTimersAsString(transaction.getRootTimer()));
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            builder.threadCpuTime(threadInfo.threadCpuTime());
            builder.threadBlockedTime(threadInfo.threadBlockedTime());
            builder.threadWaitedTime(threadInfo.threadWaitedTime());
            builder.threadAllocatedBytes(threadInfo.threadAllocatedBytes());
        }
        List<GcInfo> gcInfos = transaction.getGcInfos();
        if (gcInfos != null) {
            builder.gcInfos(Marshaling2.toJson(gcInfos, GcInfo.class));
        }
        builder.entryCount(transaction.getEntryCount());
        builder.profileSampleCount(transaction.getProfileSampleCount());
        builder.entriesExistence(Existence.YES);
        if (transaction.getProfile() == null) {
            builder.profileExistence(Existence.NO);
        } else {
            builder.profileExistence(Existence.YES);
        }
        return builder.build();
    }

    private static @Nullable String writeCustomAttributesAsString(
            ImmutableSetMultimap<String, String> attributes) throws IOException {
        if (attributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (Entry<String, Collection<String>> entry : attributes.asMap().entrySet()) {
            jg.writeArrayFieldStart(entry.getKey());
            for (String value : entry.getValue()) {
                jg.writeString(value);
            }
            jg.writeEndArray();
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static @Nullable String writeCustomDetailAsString(
            Map<String, ? extends /*@Nullable*/Object> customDetail) throws IOException {
        if (customDetail == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        new DetailMapWriter(jg).write(customDetail);
        jg.close();
        return sb.toString();
    }

    private static @Nullable String writeTimersAsString(TimerImpl rootTimer) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        rootTimer.writeValue(jg);
        jg.close();
        return sb.toString();
    }
}
