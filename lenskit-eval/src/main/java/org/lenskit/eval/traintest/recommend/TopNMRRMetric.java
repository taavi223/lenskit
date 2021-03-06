/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.eval.traintest.recommend;

import com.fasterxml.jackson.annotation.JsonCreator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.commons.lang3.StringUtils;
import org.grouplens.lenskit.util.statistics.MeanAccumulator;
import org.lenskit.api.Recommender;
import org.lenskit.eval.traintest.AlgorithmInstance;
import org.lenskit.eval.traintest.DataSet;
import org.lenskit.eval.traintest.TestUser;
import org.lenskit.eval.traintest.metrics.MetricColumn;
import org.lenskit.eval.traintest.metrics.MetricResult;
import org.lenskit.eval.traintest.metrics.TypedMetricResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compute the mean reciprocal rank.
 * 
 * This metric is registered with the type name `mrr`.  It has two configuration parameters:
 *
 * `suffix`
 * :   a suffix to append to the column name
 *
 * `goodItems`
 * :   an item selector expression. The default is the user's test items.
 */
public class TopNMRRMetric extends ListOnlyTopNMetric<TopNMRRMetric.Context> {
    private static final Logger logger = LoggerFactory.getLogger(TopNMRRMetric.class);

    private final ItemSelector goodItems;
    private final String suffix;

    /**
     * Construct a new MRR metric using the user's test items as good.
     */
    public TopNMRRMetric() {
        this(ItemSelector.userTestItems(), null);
    }

    /**
     * Construct an MRR metric from a spec.
     * @param spec The metric specl
     */
    @JsonCreator
    public TopNMRRMetric(PRMetricSpec spec) {
        this(ItemSelector.compileSelector(StringUtils.defaultString(spec.getGoodItems(), "user.testItems")),
             spec.getSuffix());
    }

    /**
     * Construct a new recall and precision top n metric
     * @param goodItems The list of items to consider "true positives", all other items will be treated
     *                  as "false positives".
     * @param sfx A suffix to append to the metric.
     */
    public TopNMRRMetric(ItemSelector goodItems, String sfx) {
        super(UserResult.class, AggregateResult.class, sfx);
        this.goodItems = goodItems;
        suffix = sfx;
    }

    @Nullable
    @Override
    public Context createContext(AlgorithmInstance algorithm, DataSet dataSet, org.lenskit.api.Recommender recommender) {
        return new Context(dataSet.getTestData().getItemDAO().getItemIds(), recommender);
    }

    @Nonnull
    @Override
    public MetricResult getAggregateMeasurements(Context context) {
        return new AggregateResult(context).withSuffix(suffix);
    }

    @Nonnull
    @Override
    public MetricResult measureUser(TestUser user, int targetLength, LongList recommendations, Context context) {
        LongSet good = goodItems.selectItems(context.universe, context.recommender, user);
        if (good.isEmpty()) {
            logger.warn("no good items for user {}", user.getUserId());
        }

        Integer rank = null;
        int i = 0;
        LongIterator iter = recommendations.iterator();
        while (iter.hasNext()) {
            i++;
            if(good.contains(iter.nextLong())) {
                rank = i;
                break;
            }
        }

        UserResult result = new UserResult(rank);
        context.addUser(result);
        return result.withSuffix(suffix);
    }

    public static class UserResult extends TypedMetricResult {
        @MetricColumn("Rank")
        public final Integer rank;

        public UserResult(Integer r) {
            rank = r;
        }

        @MetricColumn("RecipRank")
        public double getRecipRank() {
            return rank == null ? 0 : 1.0 / rank;
        }
    }

    public static class AggregateResult extends TypedMetricResult {
        /**
         * The MRR over all users.  Users for whom no good items are included, and have a reciprocal
         * rank of 0.
         */
        @MetricColumn("MRR")
        public final double mrr;
        /**
         * The MRR over those users for whom a good item could be recommended.
         */
        @MetricColumn("MRR.OfGood")
        public final double goodMRR;

        public AggregateResult(Context accum) {
            this.mrr = accum.allMean.getMean();
            this.goodMRR = accum.goodMean.getMean();
        }
    }

    public static class Context {
        private final LongSet universe;
        private final MeanAccumulator allMean = new MeanAccumulator();
        private final MeanAccumulator goodMean = new MeanAccumulator();
        private final Recommender recommender;

        Context(LongSet universe, Recommender recommender) {
            this.universe = universe;
            this.recommender = recommender;
        }

        void addUser(UserResult ur) {
            allMean.add(ur.getRecipRank());
            if (ur.rank != null) {
                goodMean.add(ur.getRecipRank());
            }
        }
    }
}
