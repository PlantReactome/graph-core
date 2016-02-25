package uk.ac.ebi.reactome.data;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.pathwaylayout.DiagramGeneratorFromDB;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.InvalidClassException;
import org.neo4j.graphdb.*;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.ebi.reactome.domain.model.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.*;

/**
 * This component is used to batch import Reactome data into neo4j.
 * This importer utilizes the Neo4j BatchInserter and the Reactome MySql adapter.
 * WARNING: The BatchInserter is not thread save, not transactional, and can not enforce any constraints
 *          while inserting data.
 * WARNING: DATA_DIR folder will be deleted at the start of data import
 *
 * Created by:
 * @author Florian Korninger (florian.korninger@ebi.ac.uk)
 * @since 16.01.16.
 */
public class ReactomeBatchImporter {

    private static final Logger fileLogger = LoggerFactory.getLogger("importFileLogger");

    private static MySQLAdaptor dba;
    private static BatchInserter batchInserter;
    private static String DATA_DIR;

    private static final String DBID = "dbId";
    private static final String STID = "stableIdentifier";
    private static final String ACCESSION = "identifier";
    private static final String NAME = "displayName";

    private static final String STOICHIOMETRY = "stoichiometry";

    private static final Map<Class, List<String>> primitiveAttributesMap = new HashMap<>();
    private static final Map<Class, List<String>> primitiveListAttributesMap = new HashMap<>();
    private static final Map<Class, List<String>> relationAttributesMap = new HashMap<>();
    private static final Map<Class, Label[]> labelMap = new HashMap<>();
    private static final Map<Long, Long> dbIds = new HashMap<>();

    private static final int width = 100;
    private static int total;

    public ReactomeBatchImporter(String host, String database, String user, String password, Integer port, String dir) {
        try {
            dba = new MySQLAdaptor(host,database,user,password,port);
            DATA_DIR = dir;
            total = (int) dba.getClassInstanceCount(ReactomeJavaConstants.DatabaseObject);
            total = total - (int) dba.getClassInstanceCount(ReactomeJavaConstants.StableIdentifier);
            total = total - (int) dba.getClassInstanceCount(ReactomeJavaConstants.PathwayDiagramItem);
            total = total - (int) dba.getClassInstanceCount(ReactomeJavaConstants.ReactionCoordinates);

            fileLogger.info("Established connection to Reactome database");
        } catch (SQLException|InvalidClassException e) {
            fileLogger.error("An error occurred while connection to the Reactome database", e);
        }
    }

    public void importAll() throws IOException {
        prepareDatabase();
        try {
            Collection<?> frontPages = dba.fetchInstancesByClass(ReactomeJavaConstants.FrontPage);
            GKInstance frontPage = (GKInstance) frontPages.iterator().next();
            Collection<?> objects = frontPage.getAttributeValuesList(ReactomeJavaConstants.frontPageItem);
            fileLogger.info("Started importing " + objects.size() + " top level pathways");
            System.out.println("Started importing " + objects.size() + " top level pathways");
            for (Object object : objects) {
                long start = System.currentTimeMillis();
                GKInstance instance = (GKInstance) object;
                if (!instance.getDisplayName().equals("Mitophagy") && !instance.getDisplayName().equals("Circadian Clock")) continue;
                importGkInstance(instance);
                long elapsedTime = System.currentTimeMillis() - start;
                int ms = (int) elapsedTime % 1000;
                int sec = (int) (elapsedTime / 1000) % 60;
                int min = (int) ((elapsedTime / (1000 * 60)) % 60);
                fileLogger.info(instance.getDisplayName() + " was processed within: " + min + " min " + sec + " sec " + ms + " ms");
            }
            fileLogger.info("All top level pathways have been imported to Neo4j");
            System.out.println("\nAll top level pathways have been imported to Neo4j");
        } catch (Exception e) {
            e.printStackTrace();
        }
        batchInserter.shutdown();
    }

    /**
     * Imports one single GkInstance into neo4j. When iterating through the relationAttributes it is possible to
     * go deeper into the GkInstance hierarchy (eg hasEvents)
     * @param instance GkInstance
     * @return Neo4j native id (generated by the BatchInserter)
     * @throws ClassNotFoundException
     */
    @SuppressWarnings("unchecked")
    private Long importGkInstance(GKInstance instance) throws ClassNotFoundException {

        if (dbIds.size() % 100 == 0 ) {
            updateProgressBar(dbIds.size(),total);
        }

        String clazzName = DatabaseObject.class.getPackage().getName() + "." + instance.getSchemClass().getName();
        Class clazz = Class.forName(clazzName);

        setUpMethods(clazz);
        // TODO saveDatabaseObject surround with try catch or should throw an error for better readability of errors
//                batchInsert can throw an illeagalArgumentExceptionnn but if not treated this will be caught here and due to
//                the recursion a wrong instance will be shown
        Long id = saveDatabaseObject(instance, clazz);

        dbIds.put(instance.getDBID(), id);

        List<String> attributes = relationAttributesMap.get(clazz);
        if (attributes != null) {
            for (String attribute : attributes) {
                try {
                    if (isValidGkInstanceAttribute(instance, attribute)) {
                        Collection<?> attributeValues = instance.getAttributeValuesList(attribute);
                        saveRelationships(id, attributeValues, attribute);
                    }
                    /**
                     * only one type of regulation is needed here, In the native data only regulatedBy exists
                     * since the type of regulation is later determined by the Object Type we can only save one
                     * otherwise relationships will be duplicated
                     */
                    else if (attribute.equals("regulatedBy") || attribute.equals("positivelyRegulatedBy")) {
                        Collection<?> referrers = instance.getReferers(ReactomeJavaConstants.regulatedEntity);
                        saveRelationships(id, referrers, "regulatedBy");
                    }
                    else if (attribute.equals("inferredTo")) {
                        Collection<?> inferredTo = instance.getAttributeValuesList(ReactomeJavaConstants.orthologousEvent);
                        if (inferredTo.size()>1) {
                            saveRelationships(id, inferredTo, "inferredTo");
                        }
                    }



                } catch (Exception e) {
                    fileLogger.error("A problem occurred when trying to retrieve data from instance " + instance.getDBID() + " "
                            + instance.getDisplayName() + "with attribute name: " + attribute, e);
                }

            }
        }
        /**
         * DEFLATING will ensure that the use of the GkInstance does not end in an OutOfMemory exception
         */
        instance.deflate();
        return id;
    }

    private void updateProgressBar(int done, int total) {
        String format = "\r%3d%% %s %c";
        char[] workchars = {'|', '/', '-', '\\'};
        int percent = (++done * 100) / total;
        StringBuilder progress = new StringBuilder(width);
        progress.append('|');
        int i = 0;
        for (; i < percent; i++) {
            progress.append("=");
        }
        for (; i < width; i++){
            progress.append(" ");
        }
        progress.append('|');
        System.out.printf(format, percent, progress, workchars[(((done - 1) % (workchars.length * 100)) /100)]);
    }

    /**
     * Saves one single GkInstance to neo4j. Only primitive attributes will be saved (Attributes that are not reference
     * to another GkInstance eg values like Strings)
     * Get the attributes map and check null is slightly faster than contains.
     * @param instance GkInstance
     * @param clazz Clazz of object that will result form converting the instance (eg Pathway, Reaction)
     * @return Neo4j native id (generated by the BatchInserter)
     */
    private Long saveDatabaseObject(GKInstance instance, Class clazz) throws IllegalArgumentException{

        Label[] labels = getLabels(clazz);
        Map<String, Object> properties = new HashMap<>();
        properties.put(DBID, instance.getDBID());
        if (instance.getDisplayName() != null) {
            properties.put(NAME, instance.getDisplayName());
        } else {
            fileLogger.error("Found an entry without display name! dbId: " + instance.getDBID());
        }

        try {
            List<String> attributes = primitiveAttributesMap.get(clazz);
            if (attributes != null) {
                for (String attribute : attributes) {
                    if (isValidGkInstanceAttribute(instance, attribute)) {
                        Object value = instance.getAttributeValue(attribute);
                        if (value == null) continue;
                        switch (attribute) {
                            case STID:
                                GKInstance stableIdentifier = (GKInstance) value;
                                String identifier = (String) stableIdentifier.getAttributeValue(ReactomeJavaConstants.identifier);
                                properties.put(attribute, identifier);
                                stableIdentifier.deflate();
                                break;
                            case "hasDiagram":
                                if (instance.getDbAdaptor() instanceof MySQLAdaptor) {
                                    DiagramGeneratorFromDB diagramHelper = new DiagramGeneratorFromDB();
                                    diagramHelper.setMySQLAdaptor((MySQLAdaptor) instance.getDbAdaptor());
                                    GKInstance diagram = diagramHelper.getPathwayDiagram(instance);
                                    properties.put(attribute, diagram != null);
                                    diagram.deflate();
                                }
                                break;
                            case "isInDisease":
                                GKInstance disease = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.disease);
                                properties.put(attribute, disease != null);
                                disease.deflate();
                                break;
                            case "isInferred":
                                GKInstance isInferredFrom = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.inferredFrom);
                                properties.put(attribute, isInferredFrom != null);
                                isInferredFrom.deflate();
                                break;
                            case "speciesName":
                                List<?> speciesList = instance.getAttributeValuesList(ReactomeJavaConstants.species);
                                if (speciesList != null && speciesList.size() > 0) {
                                    GKInstance firstSpecies = (GKInstance) speciesList.get(0);
                                    String name = firstSpecies.getDisplayName();
                                    properties.put(attribute, name);
                                }
                                for (Object gkInstance : speciesList) {
                                    GKInstance instance1 = (GKInstance) gkInstance;
                                    instance1.deflate();
                                }
                                break;
                            case "url":
                                identifier = (String) instance.getAttributeValue(ReactomeJavaConstants.identifier);
                                GKInstance referenceDatabase = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.referenceDatabase);
                                String url = (String) referenceDatabase.getAttributeValue(ReactomeJavaConstants.url);
                                properties.put(attribute, url.replace("###ID###", identifier));
                                break;
                            default:
                                properties.put(attribute, value);
                                break;
                        }
                    }
                }
            }
            attributes = primitiveListAttributesMap.get(clazz);
            if (attributes != null) {
                for (String attribute : attributes) {
                    if (isValidGkInstanceAttribute(instance, attribute)) {
                        List valueList = instance.getAttributeValuesList(attribute);
                        if (valueList == null) continue;
                        List<String> array = new ArrayList<>();
                        for (Object value : valueList) {
                            if (value == null) continue;
                            array.add((String) value);
                        }
                        properties.put(attribute, array.toArray(new String[array.size()]));
                    }
                }
            }
        } catch (Exception e) {
            fileLogger.error("A problem occurred when trying to retrieve data from GkInstance :" + instance.getDisplayName() + instance.getDBID(), e);
        }
        try {
            return batchInserter.createNode(properties, labels);
        } catch (IllegalArgumentException e) {
            fileLogger.error("A problem occurred when trying to save entry to the Grpah :" + instance.getDisplayName() + instance.getDBID(), e);
            System.exit(-1);
            throw e;
        }
    }

    /**
     * Creating a relationships between the old instance (using oldId) and its children (List objects).
     * Relationships will be created depth first, if new instance does not already exist recursion will begin
     * (newId = importGkInstance)
     * Every relationship entry will have a stoichiometry attribute, which is used as a counter. The same input of a Reaction
     * for example can be present multiple times. Instead of saving a lot of relationships we just set a counter to indicate
     * this behaviour. Since we can not query using the Batch inserter we have to iterate the collection of relationships
     * first to identify the duplicates.
     * The stoichiometry map has to utilize a helperObject because the GkInstance does not implement Comparable and
     * comparing instances will not work. In the helperObject the instance and a counter will be saved. Counter is used
     * to set stoichiometry of a relationship.
     * @param oldId Old native neo4j id, used for saving a relationship to neo4j.
     * @param objects New list of GkInstances that have relationship to the old Instance (oldId).
     * @param relationName Name of the relationship.
     * @throws ClassNotFoundException
     */
    private void saveRelationships(Long oldId, Collection objects, String relationName) throws ClassNotFoundException {
        if (objects == null || objects.isEmpty()) return;

        Map<Long, GkInstanceStoichiometryHelper> stoichiometryMap = new HashMap<>();
        for (Object object : objects) {
            if (object instanceof GKInstance) {
                GKInstance instance = (GKInstance) object;
                if(stoichiometryMap.containsKey(instance.getDBID())){
                    stoichiometryMap.get(instance.getDBID()).increment();
                } else {
                    stoichiometryMap.put(instance.getDBID(), new GkInstanceStoichiometryHelper(instance, 1));
                }
            }
        }
        for (Long dbId : stoichiometryMap.keySet()) {

            GKInstance instance = stoichiometryMap.get(dbId).getInstance();
            Long newId;
            if (!dbIds.containsKey(dbId)) {
                newId = importGkInstance(instance);
                instance.deflate();
            } else {
                newId = dbIds.get(dbId);
            }
            Map<String, Object> properties = new HashMap<>();
            properties.put(STOICHIOMETRY,stoichiometryMap.get(dbId).getCount());
            RelationshipType relationshipType = DynamicRelationshipType.withName(relationName);

            batchInserter.createRelationship(oldId, newId, relationshipType, properties);
        }
    }

    /**
     * Cleaning the old database folder, instantiate BatchInserter, create Constraints for the new DB
     */
    private void prepareDatabase() throws IOException {

        File file = cleanDatabase();
        batchInserter = BatchInserters.inserter(file);
        createConstraints();
    }

    /**
     * Creating uniqueness constraints for the new DB.
     * WARNING: Constraints can not be enforced while importing, only after batchInserter.shutdown()
     */
    private void createConstraints() {

        createSchemaConstraint(DynamicLabel.label(DatabaseObject.class.getSimpleName()), DBID);
        createSchemaConstraint(DynamicLabel.label(DatabaseObject.class.getSimpleName()), STID);

        createSchemaConstraint(DynamicLabel.label(Event.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(Event.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(Pathway.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(Pathway.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(ReactionLikeEvent.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(ReactionLikeEvent.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(Reaction.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(Reaction.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(PhysicalEntity.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(PhysicalEntity.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(Complex.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(Complex.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(EntitySet.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(EntitySet.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(GenomeEncodedEntity.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(GenomeEncodedEntity.class.getSimpleName()),STID);

        createSchemaConstraint(DynamicLabel.label(EntityWithAccessionedSequence.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(EntityWithAccessionedSequence.class.getSimpleName()),STID);
//
        createSchemaConstraint(DynamicLabel.label(ReferenceEntity.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(ReferenceEntity.class.getSimpleName()),STID);
        createIndex(DynamicLabel.label(ReferenceEntity.class.getSimpleName()), ACCESSION);

        createSchemaConstraint(DynamicLabel.label(ReferenceSequence.class.getSimpleName()),DBID);
        createSchemaConstraint(DynamicLabel.label(ReferenceSequence.class.getSimpleName()),STID);
        createIndex(DynamicLabel.label(ReferenceSequence.class.getSimpleName()), ACCESSION);
    }

    private static void createSchemaConstraint(Label label, String name) {

        try {
            batchInserter.createDeferredConstraint(label).assertPropertyIsUnique(name).create();
        } catch (ConstraintViolationException e) {
            fileLogger.warn("Could not create Constraint on " + label + " " + name);
        }
    }

    private static void createIndex(Label label, String name) {
        batchInserter.createDeferredSchemaIndex(label).on(name);
    }

    /**
     * Cleaning the Neo4j data directory
     * Deleting of file will have worked even if error occurred here.
     */
    private File cleanDatabase() {

        File dir = new File(DATA_DIR);
        try {
            if(dir.exists()) {
                FileUtils.cleanDirectory(dir);
            } else {
                FileUtils.forceMkdir(dir);
            }
        } catch (IOException | IllegalArgumentException e) {
            fileLogger.warn("An error occurred while cleaning the old database");
        }
        return dir;
    }

    /**
     * Getting all SimpleNames as neo4j labels, for given class.
     * @param clazz Clazz of object that will result form converting the instance (eg Pathway, Reaction)
     * @return Array of Neo4j LabelsCount
     */
    private Label[] getLabels(Class clazz) {

        if(!labelMap.containsKey(clazz)) {
            Label[] labels = getAllClassNames(clazz);
            labelMap.put(clazz, labels);
            return labels;
        } else {
            return labelMap.get(clazz);
        }
    }

    /**
     * Getting all SimpleNames as neo4j labels, for given class.
     * @param clazz Clazz of object that will result form converting the instance (eg Pathway, Reaction)
     * @return Array of Neo4j LabelsCount
     */
    private Label[] getAllClassNames(Class clazz) {
        List<?> superClasses = ClassUtils.getAllSuperclasses(clazz);
        List<Label> labels = new ArrayList<>();
        labels.add(DynamicLabel.label(clazz.getSimpleName()));
        for (Object object : superClasses) {
            Class superClass = (Class) object;
            if(!superClass.getSimpleName().equals("Object")) {
                labels.add(DynamicLabel.label(superClass.getSimpleName()));
            }
        }
        return labels.toArray(new Label[labels.size()]);
    }

    /**
     * Gets and separates all Methods for specific Class to create attribute map.
     * GetMethods are used to differentiate on the return type of the method.
     * If return type is "primitive" eg String than this method will be used to provide a primitiveAttributeName
     * If return type is "relationship" (Object of the model package) than this method will be used to provide a
     * relationshipAttributeName.
     * Getters are used here rather than setters because setters will return a Type[] when getting the
     * GenericParameterTypes.
     * getFields[] can not be utilized here because this method can not return inherited fields.
     * @param clazz Clazz of object that will result form converting the instance (eg Pathway, Reaction)
     */
//TODO REMOVE new attributes that are not filled by GKInstance (eg followingEvent)
    private void setUpMethods(Class clazz) {
        if(!relationAttributesMap.containsKey(clazz) && !primitiveAttributesMap.containsKey(clazz)) {
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                String methodName = method.getName();
                if (        methodName.startsWith("get")
                        && !methodName.startsWith("getSuper")
                        && !methodName.equals("getClass")
                        && !methodName.equals("getId") //getter/setter should be removed from model
                        && !methodName.equals("getDbId")
                        && !methodName.equals("getDisplayName")
                        && !methodName.equals("getTimestamp") //should be removed from model!
                        && !methodName.equals("getInferredFrom") //is in the Database, should not be populated by Physical Entities

                        && !methodName.equals("getRegulatedEntity") // is replaced by regulated by

//                        positiveRegulations
//                                negativeRegulations
//                                positivilyRegulates
//                                        negativelyRegulates

                        //Events have inferred From aswell
                        && !methodName.equals("getOrthologousEvent")
                        && !methodName.equals("getSchemaClass")
                        && !methodName.equals("getAuthor")
                        ) { //should be removed from model!

                    Type returnType = method.getGenericReturnType();
                    if (returnType instanceof ParameterizedType) {
                        ParameterizedType type = (ParameterizedType) returnType;
                        Type[] typeArguments = type.getActualTypeArguments();
                        for (Type typeArgument : typeArguments) {
                            Class typeArgClass = (Class) typeArgument;
                            if (DatabaseObject.class.isAssignableFrom(typeArgClass) ) {
                                setMethods(relationAttributesMap, clazz, method);
                            }
                            else {
                                setMethods(primitiveListAttributesMap, clazz, method);
                            }
                        }
                    } else {
                        if (DatabaseObject.class.isAssignableFrom(method.getReturnType())) {
                            setMethods(relationAttributesMap, clazz, method);
                        } else {
                            setMethods(primitiveAttributesMap, clazz, method);
                        }
                    }
                }
            }
        }
    }
    //TODO change code where this is used so that attributes like isInDisease pass here or do not log a message
    private boolean isValidGkInstanceAttribute(GKInstance instance, String attribute) {
        if(instance.getSchemClass().isValidAttribute(attribute)) {
            return true;
        } if (!attribute.equals("regulatedBy")) {
            fileLogger.warn(attribute + " is not a valid attribute for instance " + instance.getSchemClass());
        }
        return false;
    }

    /**
     * Put Attribute name into map.
     * @param map attribute map
     * @param clazz Clazz of object that will result form converting the instance (eg Pathway, Reaction)
     * @param method Method specific to clazz
     */
    private void setMethods (Map<Class,List<String>> map, Class clazz, Method method) {
        String fieldName = method.getName().substring(3);
        fieldName = lowerFirst(fieldName);
        if(map.containsKey(clazz)) {
            (map.get(clazz)).add(fieldName);
        } else {
            List<String> methodList = new ArrayList<>();
            methodList.add(fieldName);
            map.put(clazz, methodList);
        }
    }

    /**
     * First letter of string made to lower case.
     * @param str String
     * @return String
     */
    private String lowerFirst(String str) {
        if(StringUtils.isAllUpperCase(str)) {
            return str;
        }
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }



}