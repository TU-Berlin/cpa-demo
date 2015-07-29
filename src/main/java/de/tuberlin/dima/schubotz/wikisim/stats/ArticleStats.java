package de.tuberlin.dima.schubotz.wikisim.stats;

import de.tuberlin.dima.schubotz.wikisim.cpa.io.WikiDocumentDelimitedInputFormat;
import de.tuberlin.dima.schubotz.wikisim.cpa.operators.DocumentProcessor;
import de.tuberlin.dima.schubotz.wikisim.cpa.types.WikiDocument;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.util.Collector;

/**
 * Number of see also links
 * Number of out/inbound links
 * Number of words in article
 * Avg. distance of links in article
 * Number of headline in article
 */
public class ArticleStats {
    public static int getQuartileOfArticleLength(int length) {
        // borders
        int q1 = 178;
        int q2 = 362;
        int q3 = 774;

        if (length < q1)
            return 1;
        else if (length < q2)
            return 2;
        else if (length < q3)
            return 3;
        else
            return 4;

    }

    public static void main(String[] args) throws Exception {

        // set up the execution environment
        final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        if (args.length < 2) {
            System.err.println("Input/output parameters missing!");
            System.err.println("Arguments: [WIKISET] [OUTPUT-LIST] [OUTPUT-STATS]");
            System.exit(1);
        }

        String inputWikiSet = args[0];
        String outputListFilename = args[1];


        DataSource<String> text = env.readFile(new WikiDocumentDelimitedInputFormat(), inputWikiSet);

        // ArticleCounter, Links (, AvgDistance
        DataSet<ArticleTuple> articleDataSet = text.flatMap(new FlatMapFunction<String, ArticleTuple>() {
            public void flatMap(String content, Collector out) {

                WikiDocument doc = new DocumentProcessor().processDoc(content);
                if (doc == null) return;

                int words = doc.getWordMap().size();
                int outLinks = doc.getOutLinks().size();
                int headlines = doc.getHeadlines().size();
                double avgLinkDistance = doc.getAvgLinkDistance();
                double outLinksPerWords = ((double) outLinks) / ((double) words);

                out.collect(new ArticleTuple(
                                doc.getTitle(),
                                words,
                                headlines,
                                outLinks,
                                avgLinkDistance,
                                outLinksPerWords
                        )

                );

            }
        });

        if (outputListFilename.equals("print")) {
            articleDataSet.print();
        } else {
            articleDataSet.writeAsCsv(outputListFilename, "\n", "|", FileSystem.WriteMode.OVERWRITE);

            env.execute("ArticleStats");
        }

    }
}
