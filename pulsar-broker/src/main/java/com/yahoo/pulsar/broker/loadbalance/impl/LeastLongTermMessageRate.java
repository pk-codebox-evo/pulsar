/**
 * Copyright 2016 Yahoo Inc.
 *
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
package com.yahoo.pulsar.broker.loadbalance.impl;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.pulsar.broker.BrokerData;
import com.yahoo.pulsar.broker.BundleData;
import com.yahoo.pulsar.broker.ServiceConfiguration;
import com.yahoo.pulsar.broker.TimeAverageBrokerData;
import com.yahoo.pulsar.broker.TimeAverageMessageData;
import com.yahoo.pulsar.broker.loadbalance.LoadData;
import com.yahoo.pulsar.broker.loadbalance.ModularLoadManagerStrategy;

/**
 * Placement strategy which selects a broker based on which one has the least long term message rate.
 */
public class LeastLongTermMessageRate implements ModularLoadManagerStrategy {
    private static Logger log = LoggerFactory.getLogger(LeastLongTermMessageRate.class);

    // Maintain this list to reduce object creation.
    private ArrayList<String> bestBrokers;

    public LeastLongTermMessageRate(final ServiceConfiguration conf) {
        bestBrokers = new ArrayList<>();
    }

    // Form a score for a broker using its preallocated bundle data and time average data.
    // This is done by summing all preallocated long-term message rates and adding them to the broker's overall
    // long-term message rate, which is itself the sum of the long-term message rate of every allocated bundle.
    // Once the total long-term message rate is calculated, the score is then weighted by
    // max_usage < overload_threshold ? 1 / (overload_threshold - max_usage): Inf
    // This weight attempts to discourage the placement of bundles on brokers whose system resource usage is high.
    private static double getScore(final BrokerData brokerData, final ServiceConfiguration conf) {
        final double overloadThreshold = conf.getLoadBalancerBrokerOverloadedThresholdPercentage() / 100;
        double totalMessageRate = 0;
        for (BundleData bundleData : brokerData.getPreallocatedBundleData().values()) {
            final TimeAverageMessageData longTermData = bundleData.getLongTermData();
            totalMessageRate += longTermData.getMsgRateIn() + longTermData.getMsgRateOut();
        }
        final TimeAverageBrokerData timeAverageData = brokerData.getTimeAverageData();
        final double maxUsage = brokerData.getLocalData().getMaxResourceUsage();
        if (maxUsage > overloadThreshold) {
            return Double.POSITIVE_INFINITY;
        }
        // 1 / weight is the proportion of load this machine should receive in
        // proportion to a machine with no system resource burden.
        // This attempts to spread out the load in such a way that
        // machines only become overloaded if there is too much
        // load for the system to handle (e.g., all machines are
        // at least nearly overloaded).
        final double weight = maxUsage < overloadThreshold ? 1 / (overloadThreshold - maxUsage)
                : Double.POSITIVE_INFINITY;
        final double totalMessageRateEstimate = totalMessageRate + timeAverageData.getLongTermMsgRateIn()
                + timeAverageData.getLongTermMsgRateOut();
        return weight * totalMessageRateEstimate;
    }

    /**
     * Find a suitable broker to assign the given bundle to.
     * 
     * @param candidates
     *            The candidates for which the bundle may be assigned.
     * @param bundleToAssign
     *            The data for the bundle to assign.
     * @param loadData
     *            The load data from the leader broker.
     * @param conf
     *            The service configuration.
     * @return The name of the selected broker as it appears on ZooKeeper.
     */
    @Override
    public String selectBroker(final Set<String> candidates, final BundleData bundleToAssign, final LoadData loadData,
            final ServiceConfiguration conf) {
        bestBrokers.clear();
        double minScore = Double.POSITIVE_INFINITY;
        // Maintain of list of all the best scoring brokers and then randomly
        // select one of them at the end.
        for (String broker : candidates) {
            final double score = getScore(loadData.getBrokerData().get(broker), conf);
            log.info("{} got score {}", broker, score);
            if (score < minScore) {
                // Clear best brokers since this score beats the other brokers.
                bestBrokers.clear();
                bestBrokers.add(broker);
                minScore = score;
            } else if (score == minScore) {
                // Add this broker to best brokers since it ties with the best
                // score.
                bestBrokers.add(broker);
            }
        }
        if (bestBrokers.isEmpty()) {
            // All brokers are overloaded.
            // Assign randomly in this case.
            bestBrokers.addAll(candidates);
        }
        return bestBrokers.get(ThreadLocalRandom.current().nextInt(bestBrokers.size()));
    }
}
