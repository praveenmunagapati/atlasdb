/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.cassandra;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.thrift.TException;
import org.slf4j.Logger;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.impl.TracingPrefsConfig;

public class TracingQueryRunner {
    private final Logger log;
    private final TracingPrefsConfig tracingPrefs;

    public TracingQueryRunner(Logger log, TracingPrefsConfig tracingPrefs) {
        this.log = log;
        this.tracingPrefs = tracingPrefs;
    }

    @FunctionalInterface
    public interface Action<V> {
        V run() throws TException;
    }

    public <V> V run(Cassandra.Client client, Set<TableReference> tableRefs, Action<V> action) throws TException {
        if (shouldTraceQuery(tableRefs)) {
            return trace(action, client, tableRefs);
        } else {
            try {
                return action.run();
            } catch (TException e) {
                logFailedCall(tableRefs);
                throw e;
            }
        }
    }

    public <V> V run(Cassandra.Client client, TableReference tableRef, Action<V> action) throws TException {
        return run(client, ImmutableSet.of(tableRef), action);
    }

    private <V> V trace(Action<V> action, Cassandra.Client client, Set<TableReference> tableRefs) throws TException {
        ByteBuffer traceId = client.trace_next_query();
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean failed = false;
        try {
            return action.run();
        } catch (TException e) {
            failed = true;
            logFailedCall(tableRefs);
            throw e;
        } finally {
            long duration = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            logTraceResults(duration, tableRefs, traceId, failed);
        }
    }

    private boolean shouldTraceQuery(Set<TableReference> tableRefs) {
        for (TableReference tableRef : tableRefs) {
            if (tracingPrefs.shouldTraceQuery(tableRef.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private void logFailedCall(Set<TableReference> tableRefs) {
        log.error("A call to table(s) {} failed with an exception.",
                tableRefs.stream().map(TableReference::getQualifiedName).collect(Collectors.joining(", ")));
    }

    private void logTraceResults(long duration, Set<TableReference> tableRefs, ByteBuffer recvTrace, boolean failed) {
        if (failed || duration > tracingPrefs.getMinimumDurationToTraceMillis()) {
            log.error("Traced a call to {} that {}took {} ms. It will appear in system_traces with UUID={}",
                    tableRefs.stream().map(TableReference::getQualifiedName).collect(Collectors.joining(", ")),
                    failed ? "failed and " : "",
                    duration,
                    CassandraKeyValueServices.convertCassandraByteBufferUuidToString(recvTrace));
        }
    }
}
