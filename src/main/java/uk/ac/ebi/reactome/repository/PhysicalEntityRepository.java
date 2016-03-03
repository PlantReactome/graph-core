package uk.ac.ebi.reactome.repository;

import org.springframework.data.neo4j.repository.GraphRepository;
import org.springframework.stereotype.Repository;
import uk.ac.ebi.reactome.domain.model.PhysicalEntity;

/**
 * Created by:
 *
 * @author Florian Korninger (florian.korninger@ebi.ac.uk)
 * @since 28.02.16.
 */
@Repository
public interface PhysicalEntityRepository extends GraphRepository<PhysicalEntity> {

    PhysicalEntity findByDbId(Long dbId);
    PhysicalEntity findByStableIdentifier(String stableIdentifier);

}