package org.reactome.server.graph.repository;

import org.reactome.server.graph.domain.model.Pathway;
import org.reactome.server.graph.domain.model.Person;
import org.reactome.server.graph.domain.model.Publication;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.GraphRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;

/**
 * @author Florian Korninger (florian.korninger@ebi.ac.uk)
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 */
@Repository
public interface PersonRepository extends GraphRepository<Person>{

    @Query("MATCH (n:Person)-[r]->(m) WHERE n.surname in {0} AND n.firstname in {0} RETURN n, r, m")
    Collection<Person> findPersonByName(String[] name);

    @Query("MATCH (n:Person)-[r]->(m) WHERE n.surname in {0} OR n.firstname in {0} RETURN n, r, m")
    Collection<Person> queryPersonByName(String[] name);

    @Query("MATCH (n:Person{orcidId:{0}})-[r]->(m) Return n, r, m")
    Person findPersonByOrcidId(String orcidId);

    @Query("MATCH (n:Person{dbId:{0}})-[r]->(m) Return n, r, m")
    Person findPersonByDbId(Long dbId);

    @Query("MATCH (n:Person{eMailAddress:{0}})-[r]->(m) Return n, r, m")
    @Deprecated
    Person findPersonByEmail(String email);

    @Query("MATCH (n:Person{orcidId:{0}})-[:author]-(m:Publication) Return m")
    Collection<Publication> getPublicationsOfPersonByOrcidId(String orcidId);

    @Query("MATCH (n:Person{dbId:{0}})-[:author]-(m:Publication) Return m")
    Collection<Publication> getPublicationsOfPersonByDbId(Long dbId);

    @Query("MATCH (n:Person{eMailAddress:{0}})-[:author]-(m:Publication) Return m")
    Collection<Publication> getPublicationsOfPersonByEmail(String email);

    @Query("MATCH (n:Person{orcidId:{0}})-[r:author]->(m:InstanceEdit)-[e:authored]->(k:Pathway) RETURN k")
    Collection<Pathway> getAuthoredPathwaysByOrcidId(String orcidId);

    @Query("MATCH (n:Person{dbId:{0}})-[r:author]->(m:InstanceEdit)-[e:authored]->(k:Pathway) RETURN k")
    Collection<Pathway> getAuthoredPathwaysByDbId(Long dbId);

    @Query("MATCH (n:Person{eMailAddress:{0}})-[r:author]->(m:InstanceEdit)-[e:authored]->(k:Pathway) RETURN k")
    @Deprecated
    Collection<Pathway> getAuthoredPathwaysByEmail(String email);
}
