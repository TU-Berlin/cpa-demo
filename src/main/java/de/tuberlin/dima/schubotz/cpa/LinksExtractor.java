package de.tuberlin.dima.schubotz.cpa;

import de.tuberlin.dima.schubotz.cpa.contracts.DocumentProcessor;
import de.tuberlin.dima.schubotz.cpa.io.WikiDocumentDelimitedInputFormat;
import de.tuberlin.dima.schubotz.cpa.types.WikiDocument;
import de.tuberlin.dima.schubotz.cpa.utils.StringUtils;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.util.Collector;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Extracts internal links from Wikipedia articles and creates CSV for DB import
 * <p/>
 * table structure: article (primary key), link target
 */
public class LinksExtractor {

    public static String csvRowDelimiter = "\n";
    public static String csvFieldDelimiter = "\t";

    public static void main(String[] args) throws Exception {

        // set up the execution environment
        final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        if (args.length <= 1) {
            System.err.println("Input/output parameters missing!");
            System.err.println(new WikiSim().getDescription());
            System.exit(1);
        }

        String inputFilename = args[0];
        String outputFilename = args[1];

        DataSource<String> text = env.readFile(new WikiDocumentDelimitedInputFormat(), inputFilename);

        // ArticleCounter, Links (, AvgDistance
        DataSet<Tuple2<String, String>> output = text.flatMap(new FlatMapFunction<String, Tuple2<String, String>>() {
            public void flatMap(String content, Collector out) {

                Matcher m = DocumentProcessor.getPageMatcher(content);
                // if the record does not contain parsable page-xml
                if (!m.find()) return;

                // otherwise create a WikiDocument object from the xml
                WikiDocument doc = new WikiDocument();
                doc.setId(Integer.parseInt(m.group(3)));
                doc.setTitle(StringUtils.unescapeEntities(m.group(1)));
                doc.setNS(Integer.parseInt(m.group(2)));

                // skip docs from namespaces other than
                if (doc.getNS() != 0) return;

                doc.setText(StringUtils.unescapeEntities(m.group(4)));
                List<Map.Entry<String, Integer>> links = doc.getOutLinks();

                for (Map.Entry<String, Integer> outLink : links) {

                    out.collect(new Tuple2<>(
                            StringUtils.addCsvEnclosures(doc.getTitle()),
                            StringUtils.addCsvEnclosures(outLink.getKey())
                    ));
                    //System.out.println(outLink.getKey());
                }
            }
        }).distinct();

        //output.print();
        output.writeAsCsv(outputFilename, csvRowDelimiter, csvFieldDelimiter, FileSystem.WriteMode.OVERWRITE);

        env.execute("WikiLinksExtractor");
    }


}