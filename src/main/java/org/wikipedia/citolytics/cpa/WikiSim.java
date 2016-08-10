/* __
* \ \
* _ _ \ \ ______
* | | | | > \( __ )
* | |_| |/ ^ \| || |
* | ._,_/_/ \_\_||_|
* | |
* |_|
*
* ----------------------------------------------------------------------------
* "THE BEER-WARE LICENSE" (Revision 42):
* <rob ∂ CLABS dot CC> wrote this file. As long as you retain this notice you
* can do whatever you want with this stuff. If we meet some day, and you think
* this stuff is worth it, you can buy me a beer in return.
* ----------------------------------------------------------------------------
*/
package org.wikipedia.citolytics.cpa;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.wikipedia.citolytics.WikiSimAbstractJob;
import org.wikipedia.citolytics.cpa.io.WikiDocumentDelimitedInputFormat;
import org.wikipedia.citolytics.cpa.operators.CPAReducer;
import org.wikipedia.citolytics.cpa.types.WikiSimResult;
import org.wikipedia.citolytics.redirects.operators.ReduceResults;
import org.wikipedia.citolytics.redirects.operators.ReplaceRedirects;
import org.wikipedia.citolytics.redirects.single.WikiSimRedirects;
import org.wikipedia.processing.DocumentProcessor;

/**
 * Flink job for computing CPA results depended on CPI alpha values.
 *
 *
 * Arguments:
 * --input  [path]  Wikipedia XML Dump (hdfs)
 * --output [path]  Output filename (hdfs)
 * --alpha  [double,...]   CPI alpha values, each value is represented by a separate column in the output (comma separated; e.g. 0.5,1.0,1.5)
 * --reducer-threshold  [int]    Reducer threshold: Discard records with lower number of co-citations (default: 1)
 * --combiner-threshold [int]   Combiner threshold: Discard records with lower number of co-citations (default: 1)
 * --format [str]   Format of Wikipedia XML Dump (default: 2013; set to "2006" for older dumps)
 * --redirects [path]  Resolve redirects? Set path to redirects set (default: n)
 */
public class WikiSim extends WikiSimAbstractJob<WikiSimResult> {

    public static String getUsage() {
        return "Usage: --input [DATASET] --output [OUTPUT] --alpha [ALPHA1, ALPHA2, ...] " +
                "--reducer-threshold [REDUCER-THRESHOLD] --combiner-threshold [COMBINER-THRESHOLD] --format [WIKI-VERSION] " +
                "--redirects [REDIRECTS-DATESET]";
    }

    /**
     * Executes Flink job
     *
     * @param args {input, output, alpha, reducerThreshold, combinerThreshold, wiki-format, redirects}
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        new WikiSim().start(args);
    }

    public void plan() {
        jobName = "WikiSim";

        // Bug fix (heartbeat bug)
        // https://issues.apache.org/jira/browse/FLINK-2299

        Configuration clusterConfig = new Configuration();
        clusterConfig.setString(ConfigConstants.AKKA_TRANSPORT_HEARTBEAT_PAUSE, "600s");
        GlobalConfiguration.includeConfiguration(clusterConfig);

        // ---

        ParameterTool params = ParameterTool.fromArgs(args);

        if (args.length <= 1) {
            System.err.println("Error: --input and --output parameters missing!");
            System.err.println(WikiSim.getUsage());
            System.exit(1);
        }

        // Read arguments
        String inputFilename = params.get("input");
        outputFilename = params.get("output");

        // Configuration for 2nd order functions
        Configuration config = new Configuration();
        config.setString("alpha", params.get("alpha", "1.5"));
        config.setInteger("reducerThreshold", params.getInt("reducer-threshold", 0));
        config.setInteger("combinerThreshold", params.getInt("combiner-threshold", 0));

        config.setBoolean("median", true);
        config.setBoolean("wiki2006", params.get("format", "2013").equalsIgnoreCase("2006") ? true : false);

        // Read Wikipedia XML Dump
        DataSource<String> text = env.readFile(new WikiDocumentDelimitedInputFormat(), inputFilename);

        // Calculate results
        result = text.flatMap(new DocumentProcessor())
                .withParameters(config)
//                .groupBy(1, 2) // Group by Page A, Page B
                .groupBy(0) // Group by LinkTuple.hash()

                .reduceGroup(new CPAReducer())
                .withParameters(config);

        // Resolve redirects if requested
        if (params.has("redirects")) {
            jobName += " with redirects";
            result = resolveRedirects(env, result, params.get("redirects"));
        }
    }

    /**
     * Resolve Wikipedia redirects in results
     * <p/>
     * Wikipedia uses inconsistent internal links, therefore, we need to check each result record
     * for redirects, map redirects to their targets and sum up resulting duplicates.
     *
     * @param env
     * @param wikiSimResults
     * @param filename
     * @return Result set with resolved redirects
     */
    public static DataSet<WikiSimResult> resolveRedirects(ExecutionEnvironment env, DataSet<WikiSimResult> wikiSimResults, String filename) {
        // fields
        int hash = 0;
        int pageA = 1;
        int pageB = 2;
        int redirectSource = 0;
        int redirectTarget = 1;

        DataSet<Tuple2<String, String>> redirects = WikiSimRedirects.getRedirectsDataSet(env, filename);

        return wikiSimResults
                // replace page names with redirect target
                // page A
                .coGroup(redirects)
                .where(pageA)
                .equalTo(redirectSource)
                .with(new ReplaceRedirects(pageA))
                        // page B
                .coGroup(redirects)
                .where(pageB)
                .equalTo(redirectSource)
                .with(new ReplaceRedirects(pageB))
                        // sum duplicated tuples
                .groupBy(hash) // Page A, Page B (hash)
                .reduceGroup(new ReduceResults());
    }


}
