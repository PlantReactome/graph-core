package org.reactome.server.graph.qa.tests;

import org.neo4j.ogm.model.Result;
import org.reactome.server.graph.qa.QATest;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Created by:
 *
 * @author Florian Korninger (florian.korninger@ebi.ac.uk)
 * @since 14.03.16.
 */
@SuppressWarnings("unused")
@QATest
public class QualityAssuranceTest040 extends QualityAssuranceAbstract {

    @Override
    String getName() {
        return "HasMemberRelationshipDuplication";
    }

    @Override
    String getQuery() {
        return "Match (x)-[r:hasMember]->(y) OPTIONAL MATCH (x)<-[:created]-(a) WITH x,y,r,a WHERE r.stoichiometry > 1 " +
                "Return DISTINCT(x.dbId) AS dbIdA,x.stableIdentifier AS stIdA, x.displayName AS nameA, y.dbId AS dbIdB, " +
                "y.stableIdentifier AS stIdB, y.displayName AS nameB, a.displayName AS author";
    }

    @Override
    void printResult(Result result, Path path) throws IOException {
        print(result,path,"dbIdA","stIdA","nameA","dbIdB","stIdB","nameB","author");
    }
}

