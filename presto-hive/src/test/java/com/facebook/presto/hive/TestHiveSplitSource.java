/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.testing.TestingConnectorSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.stats.CounterStat;
import io.airlift.units.DataSize;
import org.apache.hadoop.fs.Path;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.facebook.presto.hive.HiveTestUtils.SESSION;
import static com.facebook.presto.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static com.facebook.presto.spi.relation.LogicalRowExpressions.TRUE;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.testing.Assertions.assertContains;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.lang.Math.toIntExact;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestHiveSplitSource
{
    private static final Executor EXECUTOR = Executors.newFixedThreadPool(5);

    @Test
    public void testOutstandingSplitCount()
    {
        HiveSplitSource hiveSplitSource = HiveSplitSource.allAtOnce(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());

        // add 10 splits
        for (int i = 0; i < 10; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }

        // remove 1 split
        assertEquals(getSplits(hiveSplitSource, 1).size(), 1);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 9);

        // remove 4 splits
        assertEquals(getSplits(hiveSplitSource, 4).size(), 4);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 5);

        // try to remove 20 splits, and verify we only got 5
        assertEquals(getSplits(hiveSplitSource, 20).size(), 5);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 0);
    }

    @Test
    public void testFail()
    {
        HiveSplitSource hiveSplitSource = HiveSplitSource.allAtOnce(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());

        // add some splits
        for (int i = 0; i < 5; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }

        // remove a split and verify
        assertEquals(getSplits(hiveSplitSource, 1).size(), 1);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 4);

        // fail source
        hiveSplitSource.fail(new RuntimeException("test"));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 4);

        // try to remove a split and verify we got the expected exception
        try {
            getSplits(hiveSplitSource, 1);
            fail("expected RuntimeException");
        }
        catch (RuntimeException e) {
            assertEquals(e.getMessage(), "test");
        }
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 4); // 3 splits + poison

        // attempt to add another split and verify it does not work
        hiveSplitSource.addToQueue(new TestSplit(99));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 4); // 3 splits + poison

        // fail source again
        hiveSplitSource.fail(new RuntimeException("another failure"));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 4); // 3 splits + poison

        // try to remove a split and verify we got the first exception
        try {
            getSplits(hiveSplitSource, 1);
            fail("expected RuntimeException");
        }
        catch (RuntimeException e) {
            assertEquals(e.getMessage(), "test");
        }
    }

    @Test
    public void testReaderWaitsForSplits()
            throws Exception
    {
        HiveSplitSource hiveSplitSource = HiveSplitSource.allAtOnce(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());

        SettableFuture<ConnectorSplit> splits = SettableFuture.create();

        // create a thread that will get a split
        CountDownLatch started = new CountDownLatch(1);
        Thread getterThread = new Thread(() -> {
            try {
                started.countDown();
                List<ConnectorSplit> batch = getSplits(hiveSplitSource, 1);
                assertEquals(batch.size(), 1);
                splits.set(batch.get(0));
            }
            catch (Throwable e) {
                splits.setException(e);
            }
        });
        getterThread.start();

        try {
            // wait for the thread to be started
            assertTrue(started.await(10, SECONDS));

            // sleep for a bit, and assure the thread is blocked
            MILLISECONDS.sleep(200);
            assertTrue(!splits.isDone());

            // add a split
            hiveSplitSource.addToQueue(new TestSplit(33));

            // wait for thread to get the split
            ConnectorSplit split = splits.get(10, SECONDS);
            assertEquals(((HiveSplit) split).getSchema().getProperty("id"), "33");
        }
        finally {
            // make sure the thread exits
            getterThread.interrupt();
        }
    }

    @Test
    public void testOutstandingSplitSize()
    {
        DataSize maxOutstandingSplitsSize = new DataSize(1, MEGABYTE);
        HiveSplitSource hiveSplitSource = HiveSplitSource.allAtOnce(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                10000,
                maxOutstandingSplitsSize,
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());

        TestSplit testSplit = new TestSplit(0);
        int testSplitSizeInBytes = testSplit.getEstimatedSizeInBytes() + testSplit.getPartitionInfo().getEstimatedSizeInBytes();

        int maxSplitCount = toIntExact(maxOutstandingSplitsSize.toBytes()) / testSplitSizeInBytes;
        for (int i = 0; i < maxSplitCount; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }

        assertEquals(getSplits(hiveSplitSource, maxSplitCount).size(), maxSplitCount);

        for (int i = 0; i < maxSplitCount; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }
        try {
            hiveSplitSource.addToQueue(new TestSplit(0));
            fail("expect failure");
        }
        catch (PrestoException e) {
            assertContains(e.getMessage(), "Split buffering for database.table exceeded memory limit");
        }
    }

    @Test(timeOut = 10_000)
    public void testEmptyBucket()
    {
        final HiveSplitSource hiveSplitSource = HiveSplitSource.bucketed(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());
        hiveSplitSource.addToQueue(new TestSplit(0, OptionalInt.of(2)));
        hiveSplitSource.noMoreSplits();
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(0), 10).size(), 0);
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(1), 10).size(), 0);
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(2), 10).size(), 1);
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(3), 10).size(), 0);
    }

    @Test
    public void testPreloadSplitsForRewindableSplitSource()
            throws Exception
    {
        // TODO: Use Session::builder after HiveTestUtils::SESSION is refactored to Session.
        ConnectorSession session = new TestingConnectorSession(
                new HiveSessionProperties(
                        new HiveClientConfig().setUseRewindableSplitSource(true),
                        new OrcFileWriterConfig(),
                        new ParquetFileWriterConfig()).getSessionProperties());
        HiveSplitSource hiveSplitSource = HiveSplitSource.bucketedRewindable(
                session,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());
        for (int i = 0; i < 10; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i, OptionalInt.of(0)));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }

        SettableFuture<List<ConnectorSplit>> splits = SettableFuture.create();

        // create a thread that will get the splits
        CountDownLatch started = new CountDownLatch(1);
        Thread getterThread = new Thread(() -> {
            try {
                started.countDown();
                List<ConnectorSplit> batch = getSplits(hiveSplitSource, OptionalInt.of(0), 10);
                splits.set(batch);
            }
            catch (Throwable e) {
                splits.setException(e);
            }
        });
        getterThread.start();

        try {
            // wait for the thread to be started
            assertTrue(started.await(10, SECONDS));

            // scheduling will not start before noMoreSplits is called to ensure we preload all splits.
            MILLISECONDS.sleep(200);
            assertFalse(splits.isDone());

            // wait for thread to get the splits after noMoreSplit signal is sent
            hiveSplitSource.noMoreSplits();
            List<ConnectorSplit> connectorSplits = splits.get(10, SECONDS);
            assertEquals(connectorSplits.size(), 0);
            assertFalse(hiveSplitSource.isFinished());

            connectorSplits = getSplits(hiveSplitSource, OptionalInt.of(0), 10);
            for (int i = 0; i < 10; i++) {
                assertEquals(((HiveSplit) connectorSplits.get(i)).getSchema().getProperty("id"), Integer.toString(i));
            }
            assertTrue(hiveSplitSource.isFinished());
        }
        finally {
            getterThread.interrupt();
        }
    }

    @Test
    public void testRewindOneBucket()
    {
        HiveSplitSource hiveSplitSource = HiveSplitSource.bucketedRewindable(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());
        for (int i = 0; i < 10; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i, OptionalInt.of(0)));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), i + 1);
        }
        hiveSplitSource.noMoreSplits();

        // Rewind when split is not retrieved.
        hiveSplitSource.rewind(new HivePartitionHandle(0));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 10);

        // Rewind when split is partially retrieved.
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(0), 5).size(), 5);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 5);
        hiveSplitSource.rewind(new HivePartitionHandle(0));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 10);

        // Rewind when split is fully retrieved
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(0), 10).size(), 10);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 0);
        hiveSplitSource.rewind(new HivePartitionHandle(0));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 10);
    }

    @Test
    public void testRewindMultipleBuckets()
    {
        HiveSplitSource hiveSplitSource = HiveSplitSource.bucketedRewindable(
                SESSION,
                "database",
                "table",
                TupleDomain.all(),
                TRUE,
                ImmutableMap.of(),
                10,
                new DataSize(1, MEGABYTE),
                new TestingHiveSplitLoader(),
                EXECUTOR,
                new CounterStat());
        for (int i = 0; i < 10; i++) {
            hiveSplitSource.addToQueue(new TestSplit(i, OptionalInt.of(1)));
            hiveSplitSource.addToQueue(new TestSplit(i, OptionalInt.of(2)));
            assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 2 * (i + 1));
        }
        hiveSplitSource.noMoreSplits();
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(1), 1).size(), 1);
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(2), 2).size(), 2);
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 17);

        // Rewind bucket 1 and test only bucket 1 is rewinded.
        hiveSplitSource.rewind(new HivePartitionHandle(1));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 18);
        assertEquals(getSplits(hiveSplitSource, OptionalInt.of(1), 1).size(), 1);

        // Rewind bucket 2 and test only bucket 2 is rewinded.
        hiveSplitSource.rewind(new HivePartitionHandle(2));
        assertEquals(hiveSplitSource.getBufferedInternalSplitCount(), 19);
    }

    private static List<ConnectorSplit> getSplits(ConnectorSplitSource source, int maxSize)
    {
        return getSplits(source, OptionalInt.empty(), maxSize);
    }

    private static List<ConnectorSplit> getSplits(ConnectorSplitSource source, OptionalInt bucketNumber, int maxSize)
    {
        if (bucketNumber.isPresent()) {
            return getFutureValue(source.getNextBatch(new HivePartitionHandle(bucketNumber.getAsInt()), maxSize)).getSplits();
        }
        else {
            return getFutureValue(source.getNextBatch(NOT_PARTITIONED, maxSize)).getSplits();
        }
    }

    private static class TestingHiveSplitLoader
            implements HiveSplitLoader
    {
        @Override
        public void start(HiveSplitSource splitSource)
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static class TestSplit
            extends InternalHiveSplit
    {
        private TestSplit(int id)
        {
            this(id, OptionalInt.empty());
        }

        private TestSplit(int id, OptionalInt bucketNumber)
        {
            super(
                    "path",
                    0,
                    100,
                    100,
                    ImmutableList.of(new InternalHiveBlock(100, ImmutableList.of())),
                    bucketNumber,
                    bucketNumber,
                    true,
                    false,
                    false,
                    new HiveSplitPartitionInfo(properties("id", String.valueOf(id)), new Path("path").toUri(), ImmutableList.of(), "partition-name", ImmutableMap.of(), Optional.empty()));
        }

        private static Properties properties(String key, String value)
        {
            Properties properties = new Properties();
            properties.put(key, value);
            return properties;
        }
    }
}
