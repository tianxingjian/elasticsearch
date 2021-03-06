/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.significant;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristic;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.search.aggregations.bucket.terms.StringTermsAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

/**
 * An aggregator of significant string values.
 */
public class SignificantStringTermsAggregator extends StringTermsAggregator {

    protected long numCollectedDocs;
    protected final SignificantTermsAggregatorFactory termsAggFactory;
    private final SignificanceHeuristic significanceHeuristic;

    public SignificantStringTermsAggregator(String name, AggregatorFactories factories, ValuesSource valuesSource, DocValueFormat format,
            BucketCountThresholds bucketCountThresholds, IncludeExclude.StringFilter includeExclude, SearchContext aggregationContext,
            Aggregator parent, SignificanceHeuristic significanceHeuristic, SignificantTermsAggregatorFactory termsAggFactory,
            List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) throws IOException {

        super(name, factories, valuesSource, null, format, bucketCountThresholds, includeExclude, aggregationContext, parent,
                SubAggCollectionMode.DEPTH_FIRST, false, pipelineAggregators, metaData);
        this.significanceHeuristic = significanceHeuristic;
        this.termsAggFactory = termsAggFactory;
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
            final LeafBucketCollector sub) throws IOException {
        return new LeafBucketCollectorBase(super.getLeafCollector(ctx, sub), null) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                super.collect(doc, bucket);
                numCollectedDocs++;
            }
        };
    }

    @Override
    public SignificantStringTerms buildAggregation(long owningBucketOrdinal) throws IOException {
        assert owningBucketOrdinal == 0;

        final int size = (int) Math.min(bucketOrds.size(), bucketCountThresholds.getShardSize());
        long supersetSize = termsAggFactory.getSupersetNumDocs();
        long subsetSize = numCollectedDocs;

        BucketSignificancePriorityQueue<SignificantStringTerms.Bucket> ordered = new BucketSignificancePriorityQueue<>(size);
        SignificantStringTerms.Bucket spare = null;
        for (int i = 0; i < bucketOrds.size(); i++) {
            final int docCount = bucketDocCount(i);
            if (docCount < bucketCountThresholds.getShardMinDocCount()) {
                continue;
            }

            if (spare == null) {
                spare = new SignificantStringTerms.Bucket(new BytesRef(), 0, 0, 0, 0, null, format);
            }

            bucketOrds.get(i, spare.termBytes);
            spare.subsetDf = docCount;
            spare.subsetSize = subsetSize;
            spare.supersetDf = termsAggFactory.getBackgroundFrequency(spare.termBytes);
            spare.supersetSize = supersetSize;
            // During shard-local down-selection we use subset/superset stats
            // that are for this shard only
            // Back at the central reducer these properties will be updated with
            // global stats
            spare.updateScore(significanceHeuristic);

            spare.bucketOrd = i;
            spare = ordered.insertWithOverflow(spare);
            if (spare == null) {
                consumeBucketsAndMaybeBreak(1);
            }
        }

        final SignificantStringTerms.Bucket[] list = new SignificantStringTerms.Bucket[ordered.size()];
        for (int i = ordered.size() - 1; i >= 0; i--) {
            final SignificantStringTerms.Bucket bucket = ordered.pop();
            // the terms are owned by the BytesRefHash, we need to pull a copy since the BytesRef hash data may be recycled at some point
            bucket.termBytes = BytesRef.deepCopyOf(bucket.termBytes);
            bucket.aggregations = bucketAggregations(bucket.bucketOrd);
            list[i] = bucket;
        }

        return new SignificantStringTerms( name, bucketCountThresholds.getRequiredSize(),
                bucketCountThresholds.getMinDocCount(), pipelineAggregators(),
                metaData(), format, subsetSize, supersetSize, significanceHeuristic, Arrays.asList(list));
    }

    @Override
    public SignificantStringTerms buildEmptyAggregation() {
        // We need to account for the significance of a miss in our global stats - provide corpus size as context
        ContextIndexSearcher searcher = context.searcher();
        IndexReader topReader = searcher.getIndexReader();
        int supersetSize = topReader.numDocs();
        return new SignificantStringTerms(name, bucketCountThresholds.getRequiredSize(), bucketCountThresholds.getMinDocCount(),
                pipelineAggregators(), metaData(), format, 0, supersetSize, significanceHeuristic, emptyList());
    }

    @Override
    public void doClose() {
        Releasables.close(bucketOrds, termsAggFactory);
    }

}

