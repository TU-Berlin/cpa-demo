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
package de.tuberlin.dima.schubotz.cpa;

import de.tuberlin.dima.schubotz.cpa.contracts.DocumentProcessor;
import de.tuberlin.dima.schubotz.cpa.contracts.calculateCPA;
import de.tuberlin.dima.schubotz.cpa.io.WikiDocumentDelimitedInputFormat;
import de.tuberlin.dima.schubotz.cpa.types.DataTypes.Result;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileSystem;

/**
 * Run with flink run -c de.tuberlin.dima.schubotz.cpa.WikiSim INPUTFILE OUTPUTFILE [alpha] [threshold]
 */
public class WikiSim {

    public static void main(String[] args) throws Exception {

        // set up the execution environment
        final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        String inputFilename = ((args.length > 0) ? args[0] : "file:///Users/malteschwarzer/IdeaProjects/cpa-demo/target/test-classes/wikiParserTest1.xml");
        String outputFilename = ((args.length > 1) ? args[1] : "file:///Users/malteschwarzer/IdeaProjects/test.txt");

        String alpha = ((args.length > 2) ? args[2] : "1.5");
        String threshold = ((args.length > 3) ? args[3] : "1");

        Configuration config = new Configuration();

        config.setInteger("threshold", Integer.valueOf(threshold));
        config.setDouble("alpha", Double.valueOf(alpha));

        DataSource<String> text = env.readFile(new WikiDocumentDelimitedInputFormat(), inputFilename);


        DataSet<Result> res = text.flatMap(new DocumentProcessor())
                .groupBy(0) // Group by LinkTuple
                .reduceGroup(new calculateCPA())
                .withParameters(config);

        //res.writeAsText(outputFilename, FileSystem.WriteMode.OVERWRITE);
        res.writeAsCsv(outputFilename, "\n", ";\t", FileSystem.WriteMode.OVERWRITE);

        env.execute("WikiSim");
    }

    public String getDescription() {
        return "Parameters: [DATASET] [OUTPUT] [ALPHA] [THRESHOLD]";
    }
}
