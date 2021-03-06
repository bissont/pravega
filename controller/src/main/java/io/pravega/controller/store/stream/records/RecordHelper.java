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

import com.google.common.base.Preconditions;
import io.pravega.shared.segment.StreamSegmentNameUtils;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.pravega.shared.segment.StreamSegmentNameUtils.computeSegmentId;

public class RecordHelper {
    
    // region scale helper methods
    /**
     * Method to validate supplied scale input. It performs a check that new ranges are identical to sealed ranges.
     *
     * @param segmentsToSeal segments to seal
     * @param newRanges      new ranges to create
     * @param currentEpoch   current epoch record
     * @return true if scale input is valid, false otherwise.
     */
    public static boolean validateInputRange(final Set<Long> segmentsToSeal,
                                             final List<Map.Entry<Double, Double>> newRanges,
                                             final EpochRecord currentEpoch) {
        boolean newRangesCheck = newRanges.stream().noneMatch(x -> x.getKey() >= x.getValue() && x.getValue() > 0);

        if (newRangesCheck) {
            List<Map.Entry<Double, Double>> oldRanges = segmentsToSeal.stream()
                    .map(segmentId -> {
                        StreamSegmentRecord segment = currentEpoch.getSegment(segmentId);
                        if (segment != null) {
                            return new AbstractMap.SimpleEntry<>(segment.getKeyStart(), segment.getKeyEnd());
                        } else {
                            return null;
                        }
                    }).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return reduce(oldRanges).equals(reduce(newRanges));
        }
        
        return false;
    }

    /**
     * Method to check that segments to seal are present in current epoch.
     *
     * @param segmentsToSeal segments to seal
     * @param currentEpoch current epoch record
     * @return true if a scale operation can be performed, false otherwise
     */
    public static boolean canScaleFor(final Set<Long> segmentsToSeal, final EpochRecord currentEpoch) {
        return segmentsToSeal.stream().allMatch(x -> currentEpoch.getSegment(x) != null);
    }

    /**
     * Method to verify if supplied epoch transition record matches the supplied input which includes segments to seal, 
     * new ranges to create. 
     * For manual scale, it will verify that segments to seal match and epoch transition record share the same segment 
     * number.
     * 
     * @param segmentsToSeal list of segments to seal
     * @param newRanges list of new ranges to create
     * @param isManualScale if it is manual scale
     * @param record epoch transition record
     * @return true if record matches supplied input, false otherwise. 
     */
    public static boolean verifyRecordMatchesInput(Set<Long> segmentsToSeal, List<Map.Entry<Double, Double>> newRanges,
                                                   boolean isManualScale, EpochTransitionRecord record) {
        // verify that supplied new range matches new range in the record
        boolean newRangeMatch = newRanges.stream().allMatch(x ->
                record.getNewSegmentsWithRange().values().stream()
                        .anyMatch(y -> y.getKey().equals(x.getKey())
                                && y.getValue().equals(x.getValue())));

        if (newRangeMatch) {
            final Set<Integer> segmentNumbersToSeal = isManualScale ? 
                    segmentsToSeal.stream().map(StreamSegmentNameUtils::getSegmentNumber).collect(Collectors.toSet()) :
                    null;
            return segmentsToSeal.stream().allMatch(segmentId -> {
                if (isManualScale) {
                    // compare segmentNumbers
                    return segmentNumbersToSeal.contains(StreamSegmentNameUtils.getSegmentNumber(segmentId));
                } else {
                    // compare segmentIds
                    return record.getSegmentsToSeal().contains(segmentId);
                }
            });
        }
        return false;
    }

    /**
     * Method to compute epoch transition record. It takes segments to seal and new ranges and all the tables and
     * computes the next epoch transition record.
     * @param currentEpoch current epoch record
     * @param segmentsToSeal segments to seal
     * @param newRanges new ranges
     * @param scaleTimestamp scale time
     * @return new epoch transition record based on supplied input
     */
    public static EpochTransitionRecord computeEpochTransition(EpochRecord currentEpoch, Set<Long> segmentsToSeal,
                                                               List<Map.Entry<Double, Double>> newRanges, long scaleTimestamp) {
        Preconditions.checkState(segmentsToSeal.stream().allMatch(currentEpoch::containsSegment), "Invalid epoch transition request");

        int newEpoch = currentEpoch.getEpoch() + 1;
        int nextSegmentNumber = currentEpoch.getSegments().stream().mapToInt(StreamSegmentRecord::getSegmentNumber).max().getAsInt() + 1;
        Map<Long, Map.Entry<Double, Double>> newSegments = new HashMap<>();
        for (int i = 0; i < newRanges.size(); i++) {
            newSegments.put(computeSegmentId(nextSegmentNumber + i, newEpoch), newRanges.get(i));
        }
        return new EpochTransitionRecord(currentEpoch.getEpoch(), scaleTimestamp, segmentsToSeal, newSegments);

    }
    // endregion

    /**
     * Helper method to compute list of continuous ranges. For example, two neighbouring key ranges where,
     * range1.high == range2.low then they are considered neighbours.
     * This method reduces input range into distinct continuous blocks.
     * @param input list of key ranges.
     * @return reduced list of key ranges.
     */
    private static List<Map.Entry<Double, Double>> reduce(List<Map.Entry<Double, Double>> input) {
        List<Map.Entry<Double, Double>> ranges = new ArrayList<>(input);
        ranges.sort(Comparator.comparingDouble(Map.Entry::getKey));
        List<Map.Entry<Double, Double>> result = new ArrayList<>();
        double low = -1.0;
        double high = -1.0;
        for (Map.Entry<Double, Double> range : ranges) {
            if (high < range.getKey()) {
                // add previous result and start a new result if prev.high is less than next.low
                if (low != -1.0 && high != -1.0) {
                    result.add(new AbstractMap.SimpleEntry<>(low, high));
                }
                low = range.getKey();
                high = range.getValue();
            } else if (high == range.getKey()) {
                // if adjacent (prev.high == next.low) then update only high
                high = range.getValue();
            } else {
                // if prev.high > next.low.
                // [Note: next.low cannot be less than 0] which means prev.high > 0
                assert low >= 0;
                assert high > 0;
                result.add(new AbstractMap.SimpleEntry<>(low, high));
                low = range.getKey();
                high = range.getValue();
            }
        }
        // add the last range
        if (low != -1.0 && high != -1.0) {
            result.add(new AbstractMap.SimpleEntry<>(low, high));
        }
        return result;
    }
}
