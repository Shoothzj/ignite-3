/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.tx.storage.state.rocksdb;

import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.internal.tx.storage.state.TxStateStorage.REBALANCE_IN_PROGRESS;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.tx.TxMeta;
import org.apache.ignite.internal.tx.storage.state.AbstractTxStateStorageTest;
import org.apache.ignite.internal.tx.storage.state.TxStateStorage;
import org.apache.ignite.lang.IgniteBiTuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tx storage test for RocksDB implementation.
 */
@ExtendWith(WorkDirectoryExtension.class)
public class RocksDbTxStateStorageTest extends AbstractTxStateStorageTest {
    @WorkDirectory
    private Path workDir;

    @Override
    protected TxStateRocksDbTableStorage createTableStorage() {
        return new TxStateRocksDbTableStorage(
                1,
                3,
                workDir,
                new ScheduledThreadPoolExecutor(1),
                Executors.newFixedThreadPool(1),
                () -> 1_000
        );
    }

    @Test
    void testRestartStorageInProgressOfRebalance() {
        TxStateStorage storage = tableStorage.getOrCreateTxStateStorage(0);

        List<IgniteBiTuple<UUID, TxMeta>> rows = List.of(
                randomTxMetaTuple(1, UUID.randomUUID()),
                randomTxMetaTuple(1, UUID.randomUUID())
        );

        fillStorage(storage, rows);

        // We emulate the situation that the rebalancing did not have time to end.
        storage.lastApplied(REBALANCE_IN_PROGRESS, REBALANCE_IN_PROGRESS);

        assertThat(storage.flush(), willCompleteSuccessfully());

        tableStorage.stop();

        tableStorage = createTableStorage();

        tableStorage.start();

        storage = tableStorage.getOrCreateTxStateStorage(0);

        checkLastApplied(storage, REBALANCE_IN_PROGRESS, REBALANCE_IN_PROGRESS, REBALANCE_IN_PROGRESS);

        checkStorageContainsRows(storage, rows);
    }
}
