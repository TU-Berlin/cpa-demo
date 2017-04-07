package org.wikipedia.citolytics.cpa.io;

import com.esotericsoftware.minlog.Log;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.operators.base.JoinOperatorBase;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.wikipedia.citolytics.cpa.operators.ComputeComplexCPI;
import org.wikipedia.citolytics.cpa.types.Recommendation;
import org.wikipedia.citolytics.cpa.types.RecommendationPair;
import org.wikipedia.citolytics.cpa.types.RecommendationSet;
import org.wikipedia.citolytics.cpa.utils.WikiSimConfiguration;
import org.wikipedia.citolytics.seealso.operators.RecommendationSetBuilder;
import org.wikipedia.citolytics.stats.ArticleStats;
import org.wikipedia.citolytics.stats.ArticleStatsTuple;

import java.util.Arrays;
import java.util.regex.Pattern;

public class WikiSimReader extends RichFlatMapFunction<String, Recommendation> {
    private int fieldScore = 9;
    private int fieldPageA = 1;
    private int fieldPageB = 2;
    private int fieldPageIdA = RecommendationPair.PAGE_A_ID_KEY;
    private int fieldPageIdB = RecommendationPair.PAGE_B_ID_KEY;

    private final Pattern delimiterPattern = Pattern.compile(Pattern.quote(WikiSimConfiguration.csvFieldDelimiter));

    @Override
    public void open(Configuration parameter) throws Exception {
        super.open(parameter);

        fieldScore = parameter.getInteger("fieldScore", 8);
        fieldPageA = parameter.getInteger("fieldPageA", 1);
        fieldPageB = parameter.getInteger("fieldPageB", 2);
    }

    @Override
    public void flatMap(String s, Collector<Recommendation> out) throws Exception {
        String[] cols = delimiterPattern.split(s);

        if (fieldScore >= cols.length || fieldPageA >= cols.length || fieldPageB >= cols.length) {
            throw new Exception("invalid col length : " + cols.length + " (score=" + fieldScore + ", a=" + fieldPageA + ", b=" + fieldPageB + "// " + s);
//            return;
        }


        try {
            String scoreString = cols[fieldScore];
            Double score = Double.valueOf(scoreString);

            // Collect full pair (A -> B and B -> A)
            out.collect(new Recommendation(cols[fieldPageA], cols[fieldPageB], score, Integer.valueOf(cols[fieldPageIdA]), Integer.valueOf(cols[fieldPageIdB])));
            out.collect(new Recommendation(cols[fieldPageB], cols[fieldPageA], score, Integer.valueOf(cols[fieldPageIdB]), Integer.valueOf(cols[fieldPageIdA])));

        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Score field = " + fieldScore + "; cols length = " + cols.length + "; Raw = " + s + "\nArray =" + Arrays.toString(cols) + "\n" + e.getMessage());

        }
    }


    public static DataSet<Recommendation> readWikiSimOutput(ExecutionEnvironment env, String filename,
                                                            int fieldPageA, int fieldPageB, int fieldScore) throws Exception {

        Log.info("Reading WikiSim from " + filename);

        Configuration config = new Configuration();

        config.setInteger("fieldPageA", fieldPageA);
        config.setInteger("fieldPageB", fieldPageB);
        config.setInteger("fieldScore", fieldScore);

        // Read recommendation from files
        return env.readTextFile(filename)
                .flatMap(new WikiSimReader())
                .withParameters(config);
    }

    public static DataSet<RecommendationSet> buildRecommendationSets(ExecutionEnvironment env,
                                                                     DataSet<Recommendation> recommendations,
                                                                     int topK, String cpiExpr, String articleStatsFilename) throws Exception {
        // Compute complex CPI with expression
        if(cpiExpr != null && articleStatsFilename != null) {
            // TODO redirects?
            DataSet<ArticleStatsTuple> stats = ArticleStats.getArticleStatsFromFile(env, articleStatsFilename);

            // Total articles
            long count = stats.count();

            // TODO JoinHint? Currently using left hybrid build second
            recommendations = recommendations
                    .leftOuterJoin(stats, JoinOperatorBase.JoinHint.BROADCAST_HASH_SECOND)
                    .where(Recommendation.RECOMMENDATION_TITLE_KEY)
                    .equalTo(ArticleStatsTuple.ARTICLE_NAME_KEY)
                    .with(new ComputeComplexCPI(count, cpiExpr));
        }

        return recommendations
                .groupBy(Recommendation.SOURCE_TITLE_KEY) // Using HashPartition Sort on [0; ASC] TODO Maybe use reduce()
                .reduceGroup(new RecommendationSetBuilder(topK));

    }
}