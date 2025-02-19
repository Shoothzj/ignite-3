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

package org.apache.ignite.internal.catalog.storage;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willBe;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.internal.catalog.Catalog;
import org.apache.ignite.internal.metastorage.MetaStorageManager;
import org.apache.ignite.internal.metastorage.impl.StandaloneMetaStorageManager;
import org.apache.ignite.internal.metastorage.server.SimpleInMemoryKeyValueStorage;
import org.apache.ignite.internal.tostring.S;
import org.apache.ignite.internal.vault.VaultManager;
import org.apache.ignite.internal.vault.inmemory.InMemoryVaultService;
import org.apache.ignite.lang.IgniteInternalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests to verify {@link UpdateLogImpl}. */
@SuppressWarnings("ConstantConditions")
class UpdateLogImplTest {

    private MetaStorageManager metastore;

    private VaultManager vault;

    @BeforeEach
    void setUp() {
        vault = new VaultManager(new InMemoryVaultService());

        metastore = StandaloneMetaStorageManager.create(vault, new SimpleInMemoryKeyValueStorage("test"));

        vault.start();
        metastore.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        metastore.stop();
        vault.stop();
    }

    @Test
    public void logReplayedOnStart() throws Exception {
        // first, let's append a few entries to the log
        UpdateLogImpl updateLog = createUpdateLogImpl();

        long revisionBefore = metastore.appliedRevision();

        updateLog.registerUpdateHandler((update, ts, causalityToken) -> {/* no-op */});
        updateLog.start();

        assertThat("Watches were not deployed", metastore.deployWatches(), willCompleteSuccessfully());

        List<VersionedUpdate> expectedVersions = List.of(
                new VersionedUpdate(1, 1L, List.of(new TestUpdateEntry("foo"))),
                new VersionedUpdate(2, 2L, List.of(new TestUpdateEntry("bar")))
        );

        for (VersionedUpdate update : expectedVersions) {
            assertThat(updateLog.append(update), willBe(true));
        }

        // and wait till metastore apply necessary revision
        assertTrue(
                waitForCondition(
                        () -> metastore.appliedRevision() - expectedVersions.size() == revisionBefore,
                        TimeUnit.SECONDS.toMillis(5)
                )
        );

        updateLog.stop();

        // now let's create new component over a stuffed vault/metastore
        // and check if log is replayed on start
        updateLog = createUpdateLogImpl();

        List<VersionedUpdate> actualVersions = new ArrayList<>();
        List<Long> actualCausalityTokens = new ArrayList<>();

        updateLog.registerUpdateHandler((update, ts, causalityToken) -> {
            actualVersions.add(update);
            actualCausalityTokens.add(causalityToken);
        });

        updateLog.start();

        assertEquals(expectedVersions, actualVersions);
        assertEquals(List.of(revisionBefore + 1, revisionBefore + 2), actualCausalityTokens);
    }

    private UpdateLogImpl createUpdateLogImpl() {
        return new UpdateLogImpl(metastore);
    }

    @Test
    public void exceptionIsThrownOnStartIfHandlerHasNotBeenRegistered() {
        UpdateLogImpl updateLog = createUpdateLogImpl();

        IgniteInternalException ex = assertThrows(
                IgniteInternalException.class,
                updateLog::start
        );

        assertThat(
                ex.getMessage(),
                containsString("Handler must be registered prior to component start")
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    public void appendAcceptsUpdatesInOrder(int startVersion) throws Exception {
        UpdateLogImpl updateLog = createUpdateLogImpl();

        List<Integer> appliedVersions = new ArrayList<>();
        List<Long> causalityTokens = new ArrayList<>();

        updateLog.registerUpdateHandler((update, ts, causalityToken) -> {
            appliedVersions.add(update.version());
            causalityTokens.add(causalityToken);
        });

        long revisionBefore = metastore.appliedRevision();

        updateLog.start();

        assertThat("Watches were not deployed", metastore.deployWatches(), willCompleteSuccessfully());

        // first update should always be successful
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion)), willBe(true));

        // update of the same version should not be accepted
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion)), willBe(false));

        // update of the version lower than the last applied should not be accepted
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion - 1)), willBe(false));

        // update of the version creating gap should not be accepted
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion + 2)), willBe(false));

        // regular update of next version should be accepted
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion + 1)), willBe(true));

        // now the gap is filled, thus update should be accepted as well
        assertThat(updateLog.append(singleEntryUpdateOfVersion(startVersion + 2)), willBe(true));

        List<Integer> expectedVersions = List.of(startVersion, startVersion + 1, startVersion + 2);
        List<Long> expectedTokens = List.of(revisionBefore + 1, revisionBefore + 2, revisionBefore + 3);

        // wait till necessary revision is applied
        assertTrue(
                waitForCondition(
                        () -> metastore.appliedRevision() - expectedVersions.size() == revisionBefore,
                        TimeUnit.SECONDS.toMillis(5)
                )
        );

        assertThat(appliedVersions, equalTo(expectedVersions));
        assertThat(causalityTokens, equalTo(expectedTokens));
    }

    private static VersionedUpdate singleEntryUpdateOfVersion(int version) {
        return new VersionedUpdate(version, 1, List.of(new TestUpdateEntry("foo_" + version)));
    }

    static class TestUpdateEntry implements UpdateEntry {
        private static final long serialVersionUID = 4865078624964906600L;

        final String payload;

        TestUpdateEntry(String payload) {
            this.payload = payload;
        }

        @Override
        public Catalog applyUpdate(Catalog catalog) {
            return catalog;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            TestUpdateEntry that = (TestUpdateEntry) o;

            return payload.equals(that.payload);
        }

        @Override
        public int hashCode() {
            return payload.hashCode();
        }

        @Override
        public String toString() {
            return S.toString(this);
        }
    }
}
