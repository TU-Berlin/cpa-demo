package org.wikipedia.citolytics.clickstream.utils;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.wikipedia.citolytics.clickstream.operators.ClickStreamDataSetReader;
import org.wikipedia.citolytics.clickstream.types.ClickStreamTranslateTuple;
import org.wikipedia.citolytics.clickstream.types.ClickStreamTuple;
import org.wikipedia.citolytics.multilang.LangLinkTuple;
import org.wikipedia.citolytics.multilang.MultiLang;

import java.util.HashMap;

/**
 * Using Wikipedia ClickStream data set as relevance judgements.
 *
 * @link General information: http://meta.wikimedia.org/wiki/Research:Wikipedia_clickstream
 * @link Download: http://figshare.com/articles/Wikipedia_Clickstream/1305770
 * @link Examples: http://ewulczyn.github.io/Wikipedia_Clickstream_Getting_Started/
 *
 * Fields: rev_id, curr_id, n, prev_title (referrer), curr_title, type
 */
public class ClickStreamHelper {

    /**
     * Returns data set of click stream tuples
     *
     * @param env ExecutionEnvironment
     * @param filename Path to data set (separate multiple files by comma)
     * @return
     */
    public static DataSet<ClickStreamTuple> getClickStreamDataSet(ExecutionEnvironment env, String filename) {
        return getTranslatedClickStreamDataSet(env, filename, null, null);
    }

    public static DataSet<ClickStreamTuple> getTranslatedClickStreamDataSet(ExecutionEnvironment env, String filename, String lang, String langLinksFilename) {
        DataSet<ClickStreamTranslateTuple> translateInput = readClickStreamDataSetInputs(env, filename);

        // Translate if requested
        if(lang != null && langLinksFilename != null) {

            // Load enwiki language links
            DataSet<LangLinkTuple> langLinks = MultiLang.readLangLinksDataSet(env, langLinksFilename, lang);

            // Translate article name and target name
            translateInput = translateInput
                // article name
                .join(langLinks)
                .where(ClickStreamTranslateTuple.ARTICLE_ID_KEY)
                .equalTo(LangLinkTuple.PAGE_ID_KEY)
                .with((JoinFunction<ClickStreamTranslateTuple, LangLinkTuple, ClickStreamTranslateTuple>) (cs, ll) -> {
                    // Replace names with translated values
                    cs.setField(ll.getTargetTitle(), ClickStreamTranslateTuple.ARTICLE_NAME_KEY);


                    System.out.println("TRANLATE (articleName) " + cs.getArticleName() + " => " + ll.getTargetTitle() + ": " + cs);
                    return cs;
                })
                // target name
                .join(langLinks)
                .where(ClickStreamTranslateTuple.TARGET_ID_KEY)
                .equalTo(LangLinkTuple.PAGE_ID_KEY)
                .with((JoinFunction<ClickStreamTranslateTuple, LangLinkTuple, ClickStreamTranslateTuple>) (cs, ll) -> {
                    // Replace names with translated values
                    cs.setField(ll.getTargetTitle(), ClickStreamTranslateTuple.TARGET_NAME_KEY);


                    System.out.println("TRANLATE (targetName) " + cs.getTargetName() + " => " + ll.getTargetTitle() + ": " + cs);
                    return cs;
                });
                ;
        }

//        try {
//            translateInput.print();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // Transform translateInput into normal input
        DataSet<ClickStreamTuple> input = translateInput.flatMap(new FlatMapFunction<ClickStreamTranslateTuple, ClickStreamTuple>() {
            @Override
            public void flatMap(ClickStreamTranslateTuple in, Collector<ClickStreamTuple> out) throws Exception {
                ClickStreamTuple t = new ClickStreamTuple(
                        in.getArticleName(), //referrerName,
                        in.getArticleId(), //referrerId,
                        0,
                        ClickStreamDataSetReader.getOutMap(in.getTargetName(), in.getClicks()),
                        ClickStreamDataSetReader.getOutMap(in.getTargetName(), in.getTargetId())
                );

                System.out.println("ClickStreamTuple = " + t);

                out.collect(t);

                // Impressions
                if (in.getClicks() > 0) {
                    t = new ClickStreamTuple(in.getTargetName(), in.getTargetId(), in.getClicks(), new HashMap<>(), new HashMap<>());
                    System.out.println("Impressions = " + t);
                    out.collect(t);
                }
            }
        });

//        try {
//            input.print();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // Group and reduce click streams
        return input
                .groupBy(0)
                .reduce(new ReduceFunction<ClickStreamTuple>() {
                    @Override
                    public ClickStreamTuple reduce(ClickStreamTuple a, ClickStreamTuple b) throws Exception {

                        HashMap<String, Integer> outClicks = new HashMap<>();
                        outClicks.putAll(a.getOutClicks());
                        outClicks.putAll(b.getOutClicks());

                        HashMap<String, Integer> outIds = new HashMap<>();
                        outIds.putAll(a.getOutIds());
                        outIds.putAll(b.getOutIds());

                        ClickStreamTuple t = new ClickStreamTuple(
                                a.getArticleName(),
                                a.getArticleId(),
                                a.getImpressions() + b.getImpressions(),
                                outClicks,
                                outIds);

                        // TODO With is this printed twice?
                        System.out.println("ClickStreamTuple (reduce) = " + t + "\na = " + a + "\nb= " + b);

                        return t;
                    }
                })
                ;
    }

    /**
     * Helper methods that enables reading from multiple inputs
     *
     * @param env
     * @param filename Separate multiple inputs by comma
     * @return
     */
    private static DataSet<ClickStreamTranslateTuple> readClickStreamDataSetInputs(ExecutionEnvironment env, String filename) {
        // Read input(s)
        DataSet<ClickStreamTranslateTuple> input = null;
        for(String f: filename.split(",")) {
            // Read current input
            DataSet<ClickStreamTranslateTuple> currentInput = env.readTextFile(f)
                    .flatMap(new ClickStreamDataSetReader());
            if(input == null) {
                // Set if it is first input
                input = currentInput;
            } else {
                // Otherwise union with previous inputs
                input = input.union(currentInput);
            }
        }
        return input;
    }

}
