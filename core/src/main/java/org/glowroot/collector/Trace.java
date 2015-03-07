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
package org.glowroot.collector;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSetMultimap;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.config.MarshalingRoutines;

@Value.Immutable
@Json.Marshaled
@Json.Import(MarshalingRoutines.class)
public abstract class Trace {

    public abstract String id();
    abstract boolean active();
    // "partial" means "partial stored" but no longer currently active
    public abstract boolean partial();
    public abstract long startTime();
    public abstract long captureTime();
    public abstract long duration(); // nanoseconds
    public abstract String transactionType();
    public abstract String transactionName();
    public abstract String headline();
    public abstract @Nullable String error();
    public abstract @Nullable String user();
    public abstract @Nullable String customAttributes(); // json data
    public abstract @Nullable String customDetail(); // json data
    public abstract @Nullable String timers(); // json data
    public abstract @Nullable Long threadCpuTime(); // nanoseconds
    public abstract @Nullable Long threadBlockedTime(); // nanoseconds
    public abstract @Nullable Long threadWaitedTime(); // nanoseconds
    public abstract @Nullable Long threadAllocatedBytes();
    public abstract @Nullable String gcInfos(); // json data
    public abstract long entryCount();
    public abstract long profileSampleCount();
    abstract Existence entriesExistence();
    abstract Existence profileExistence();
    @Value.Default
    public ImmutableSetMultimap<String, String> customAttributesForIndexing() {
        return ImmutableSetMultimap.of();
    }
}
