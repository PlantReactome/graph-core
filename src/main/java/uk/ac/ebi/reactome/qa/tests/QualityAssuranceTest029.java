package uk.ac.ebi.reactome.qa.tests;

import org.neo4j.ogm.model.Result;
import uk.ac.ebi.reactome.qa.QATest;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Created by:
 *
 * @author Florian Korninger (florian.korninger@ebi.ac.uk)
 * @since 08.03.16.
 */
@SuppressWarnings("unused")
@QATest
public class QualityAssuranceTest029 extends QualityAssuranceAbstract {

    @Override
    String getName() {
        return "CompartmentAndGoCellularComponentPointToSameEntry";
    }

    @Override
    String getQuery() {
        return "Match (n)-[r:compartment|goCellularComponent]->(x),(n)-[e]->(x),(n)<-[:created]-(a) " +
                "RETURN DISTINCT(n.dbId) AS dbIdA,n.stableIdentifier AS stIdA, n.displayName AS nameA, x.dbId AS dbIdB, " +
                "x.stableIdentifier AS stIdB, x.displayName AS nameB, a.displayName AS author";
    }

    @Override
    void printResult(Result result, Path path) throws IOException {
        print(result,path,"dbIdA","stIdA","nameA","dbIdB","stIdB","nameB","author");
    }
}

