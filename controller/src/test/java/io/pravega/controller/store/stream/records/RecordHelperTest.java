/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.store.stream.records;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.pravega.shared.segment.StreamSegmentNameUtils;
import org.junit.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordHelperTest {
    @Test
    public void sealedSegmentShardingTest() {
        Map<Integer, SealedSegmentsMapShard> mapshards = new HashMap<>();

        int shard = SealedSegmentsMapShard.getShardNumber(StreamSegmentNameUtils.computeSegmentId(10, 10), 100);
        assertEquals(0, shard);

        Map<Long, Long> map = new HashMap<>();
        map.put(StreamSegmentNameUtils.computeSegmentId(10, 10), 100L);
        mapshards.put(shard, SealedSegmentsMapShard.builder().shardNumber(shard).sealedSegmentsSizeMap(map).build());

        shard = SealedSegmentsMapShard.getShardNumber(StreamSegmentNameUtils.computeSegmentId(10, 1000), 100);
        assertEquals(10, shard);

        map = new HashMap<>();
        map.put(StreamSegmentNameUtils.computeSegmentId(10, 1000), 100L);
        mapshards.put(shard, SealedSegmentsMapShard.builder().shardNumber(shard).sealedSegmentsSizeMap(map).build());

        long segmentId = StreamSegmentNameUtils.computeSegmentId(10000, 1000);
        shard = SealedSegmentsMapShard.getShardNumber(segmentId, 100);
        assertEquals(10, shard);

        mapshards.get(shard).addSealedSegmentSize(segmentId, 100L);
        assertEquals(100L, mapshards.get(shard).getSize(segmentId));
    }

    @Test
    public void retentionSetRecordTest() {
        RetentionSet retentionSet = new RetentionSet(Collections.emptyList());

        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(0L, 0L, Collections.emptyMap()));
        assertTrue(!retentionSet.getRetentionRecords().isEmpty());

        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(100L, 100L, Collections.emptyMap()));
        assertEquals(2, retentionSet.getRetentionRecords().size());
        assertEquals(100L, retentionSet.getLatest().recordingTime);
        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(99L, 99L, Collections.emptyMap()));
        assertEquals(2, retentionSet.getRetentionRecords().size());
        assertEquals(100L, retentionSet.getLatest().recordingTime);

        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(1000L, 1000L, Collections.emptyMap()));
        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(10000L, 10000L, Collections.emptyMap()));
        retentionSet = RetentionSet.addReferenceToStreamCutIfLatest(retentionSet, new RetentionStreamCutRecord(100000L, 100000L, Collections.emptyMap()));
        assertEquals(5, retentionSet.getRetentionRecords().size());
        assertEquals(100000L, retentionSet.getLatest().recordingTime);

        List<StreamCutReferenceRecord> before = retentionSet.retentionRecordsBefore(new StreamCutReferenceRecord(99L, 0L));
        assertEquals(1, before.size());
        assertEquals(0L, before.get(0).recordingTime);

        before = retentionSet.retentionRecordsBefore(new StreamCutReferenceRecord(9999L, 0L));
        assertEquals(3, before.size());
        assertEquals(1000L, before.get(2).recordingTime);
        
        before = retentionSet.retentionRecordsBefore(new StreamCutReferenceRecord(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(5, before.size());
        assertEquals(100000L, before.get(4).recordingTime);

        StreamCutReferenceRecord record = retentionSet.findStreamCutReferenceForTime(101L);
        assertEquals(record.recordingTime, 100L);
        record = retentionSet.findStreamCutReferenceForSize(1000L);
        assertEquals(record.recordingSize, 1000L);

        retentionSet = RetentionSet.removeStreamCutBefore(retentionSet, new StreamCutReferenceRecord(9999L, 0L));
        assertEquals(2, retentionSet.getRetentionRecords().size());
        assertEquals(100000L, retentionSet.getLatest().recordingTime);

        retentionSet = RetentionSet.removeStreamCutBefore(retentionSet, new StreamCutReferenceRecord(9999L, 0L));
        assertEquals(2, retentionSet.getRetentionRecords().size());
        assertEquals(100000L, retentionSet.getLatest().recordingTime);
     
        retentionSet = RetentionSet.removeStreamCutBefore(retentionSet, new StreamCutReferenceRecord(Long.MAX_VALUE, 0L));
        assertEquals(0, retentionSet.getRetentionRecords().size());
    }

    @Test
    public void scaleHelperMethodTest() {
        long timestamp = System.currentTimeMillis();
        final double keyRangeChunk = 1.0 / 5;
        List<StreamSegmentRecord> list = Lists.newArrayList(new StreamSegmentRecord(0, 0, timestamp, 0.0, keyRangeChunk),
                new StreamSegmentRecord(1, 0, timestamp, keyRangeChunk, 2 * keyRangeChunk),
                new StreamSegmentRecord(2, 0, timestamp, 2 * keyRangeChunk, 3 * keyRangeChunk),
                new StreamSegmentRecord(3, 0, timestamp, 3 * keyRangeChunk, 4 * keyRangeChunk),
                new StreamSegmentRecord(4, 0, timestamp, 4 * keyRangeChunk, 1.0));
        EpochRecord epochRecord = new EpochRecord(0, 0, list, timestamp);

        assertFalse(RecordHelper.canScaleFor(Sets.newHashSet(0L, 1L, 5L), epochRecord));
        assertTrue(RecordHelper.canScaleFor(Sets.newHashSet(0L, 1L, 4L), epochRecord));

        List<Map.Entry<Double, Double>> newRanges = new ArrayList<>();
        // 1. empty newRanges
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L), newRanges, epochRecord));

        // 2. simple mismatch
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, keyRangeChunk));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L), newRanges, epochRecord));

        // 3. simple valid match
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        Set<Long> segmentsToSeal = new HashSet<>(Lists.newArrayList(0L, 1L));
        assertTrue(RecordHelper.validateInputRange(segmentsToSeal, newRanges, epochRecord));
        EpochTransitionRecord epochTransitionRecord = RecordHelper.computeEpochTransition(epochRecord,
                segmentsToSeal, newRanges, timestamp);
        assertEquals(0, epochTransitionRecord.getActiveEpoch());
        assertEquals(1, epochTransitionRecord.getNewEpoch());
        assertEquals(ImmutableSet.copyOf(segmentsToSeal), epochTransitionRecord.getSegmentsToSeal());
        assertEquals(1, epochTransitionRecord.getNewSegmentsWithRange().size());
        assertTrue(epochTransitionRecord.getNewSegmentsWithRange().containsKey(StreamSegmentNameUtils.computeSegmentId(5, 1)));
        assertEquals(newRanges.get(0), epochTransitionRecord.getNewSegmentsWithRange().get(StreamSegmentNameUtils.computeSegmentId(5, 1)));

        assertTrue(RecordHelper.verifyRecordMatchesInput(segmentsToSeal, newRanges, true, epochTransitionRecord));
        Set<Long> duplicate = segmentsToSeal.stream().map(x -> StreamSegmentNameUtils.computeSegmentId(StreamSegmentNameUtils.getSegmentNumber(x), 3)).collect(Collectors.toSet());
        assertFalse(RecordHelper.verifyRecordMatchesInput(duplicate, newRanges, false, epochTransitionRecord));
        assertTrue(RecordHelper.verifyRecordMatchesInput(duplicate, newRanges, true, epochTransitionRecord));

        // 4. valid 2 disjoint merges
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 1.0));
        assertTrue(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L, 3L, 4L), newRanges, epochRecord));

        // 5. valid 1 merge and 1 disjoint
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(keyRangeChunk, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 1.0));
        assertTrue(RecordHelper.validateInputRange(Sets.newHashSet(1L, 3L, 4L), newRanges, epochRecord));

        // 6. valid 1 merge, 2 splits
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 0.7));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.7, 0.8));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.8, 0.9));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.9, 1.0));
        assertTrue(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L, 3L, 4L), newRanges, epochRecord));

        // 7. 1 merge, 1 split and 1 invalid split
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 0.7));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.7, 0.8));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.8, 0.9));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.9, 0.99));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L, 3L, 4L), newRanges, epochRecord));

        // 8. valid unsorted segments to seal
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 0.7));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.7, 0.8));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.8, 0.9));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.9, 1.0));
        assertTrue(RecordHelper.validateInputRange(Sets.newHashSet(4L, 0L, 1L, 3L), newRanges, epochRecord));

        // 9. valid unsorted new ranges
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.9, 1.0));
        newRanges.add(new AbstractMap.SimpleEntry<>(3 * keyRangeChunk, 0.7));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.7, 0.8));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 2 * keyRangeChunk));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.8, 0.9));
        assertTrue(RecordHelper.validateInputRange(Sets.newHashSet(4L, 0L, 1L, 3L), newRanges, epochRecord));

        // 10. invalid input range low == high
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 0.2));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.2));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.4));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L), newRanges, epochRecord));

        // 11. invalid input range low > high
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.0, 0.2));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.3, 0.2));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.4));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(0L, 1L), newRanges, epochRecord));

        // 12. invalid overlapping key ranges
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.4));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.3, 3 * keyRangeChunk));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(1L, 2L), newRanges, epochRecord));

        // 13. invalid overlapping key ranges -- a contains b
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.4));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.3, 0.33));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(1L), newRanges, epochRecord));

        // 14. invalid overlapping key ranges -- b contains a (with b.low == a.low)
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.33));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.4));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(1L), newRanges, epochRecord));

        // 15. invalid overlapping key ranges b.low < a.high
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.35));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.3, 0.4));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(1L), newRanges, epochRecord));

        // 16. invalid overlapping key ranges.. a.high < b.low
        newRanges = new ArrayList<>();
        newRanges.add(new AbstractMap.SimpleEntry<>(0.2, 0.25));
        newRanges.add(new AbstractMap.SimpleEntry<>(0.3, 0.4));
        assertFalse(RecordHelper.validateInputRange(Sets.newHashSet(1L), newRanges, epochRecord));
    }
}
