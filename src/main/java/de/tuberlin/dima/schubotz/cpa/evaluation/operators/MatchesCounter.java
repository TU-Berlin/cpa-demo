package de.tuberlin.dima.schubotz.cpa.evaluation.operators;

import de.tuberlin.dima.schubotz.cpa.evaluation.types.EvaluationResult;
import de.tuberlin.dima.schubotz.cpa.evaluation.types.ListResult;
import de.tuberlin.dima.schubotz.cpa.types.list.StringListValue;
import org.apache.commons.collections.ListUtils;
import org.apache.flink.api.common.functions.CoGroupFunction;
import org.apache.flink.types.StringValue;
import org.apache.flink.util.Collector;

import java.util.Iterator;

/**
 * Join EvaluationResult set to EvaluationFinalResult, intersect results and count matches.
 */
public class MatchesCounter implements CoGroupFunction<EvaluationResult, ListResult, EvaluationResult> {

    public static int[] topKs = new int[]{10, 5, 1};
    int listKey;
    int matchesKey;

    public MatchesCounter(int[] topKs, int listKey, int matchesKey) {
        this.topKs = topKs;
        this.listKey = listKey;
        this.matchesKey = matchesKey;
    }

    @Override
    public void coGroup(Iterable<EvaluationResult> first, Iterable<ListResult> second, Collector<EvaluationResult> out) throws Exception {

        Iterator<EvaluationResult> iterator1 = first.iterator();
        Iterator<ListResult> iterator2 = second.iterator();

        EvaluationResult record = null;
        ListResult join = null;


        if (iterator1.hasNext()) {
            record = iterator1.next();
            StringListValue recordList = record.getField(EvaluationResult.SEEALSO_LIST_KEY);

            if (iterator2.hasNext()) {
                join = iterator2.next();

                StringListValue joinList = (StringListValue) join.getField(1);
                int joinLength = joinList.size();

                // Set MRR
                record.setField(getMeanReciprocalRank(joinList, recordList), listKey + 2);

                record.setField(joinList, listKey);
                record.setField(joinLength, listKey + 1);

                int matchesCount = -1;

                for (int i = 0; i < topKs.length; i++) {
                    int topK = topKs[i];

                    if (joinLength < topK)
                        topK = joinLength;

                    // If matchesCount is already 0, avoid intersection
                    if (matchesCount != 0) {
                        matchesCount = ListUtils.intersection(recordList, joinList.subList(0, topK)).size();
                    }

                    record.setMatchesCount(matchesCount, matchesKey, i);
                }

            }

            out.collect(record);
        }
    }

    public static double getMeanReciprocalRank(StringListValue sortedResults, StringListValue correctResponseList) {
        double rank = 0;

        if (correctResponseList.size() == 0)
            return 0;

        for (StringValue correct : correctResponseList) {
            rank += getMeanReciprocalRank(sortedResults, correct);
        }

        return rank / correctResponseList.size();
    }

    public static double getMeanReciprocalRank(StringListValue sortedResults, StringValue correctResponse) {
        int rank = sortedResults.indexOf(correctResponse) + 1;
        double mrr = 0;

        if (rank > 0) {
            mrr = 1.0 / rank;
        }
        return mrr;
    }
}
