/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.mongodb;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

/**
 * Provides utility methods for handling data held in MongoDB.
 * 
 * @author amresh.singh
 */
final class MongoDBDataHandler
{

    /** The log. */
    private static Log log = LogFactory.getLog(MongoDBDataHandler.class);

    /**
     * Gets the entity from document.
     * 
     * @param entityClass
     *            the entity class
     * @param m
     *            the m
     * @param document
     *            the document
     * @param relations
     *            the relations
     * @return the entity from document
     */
    Object getEntityFromDocument(Class<?> entityClass, EntityMetadata m, DBObject document, List<String> relations)
    {
        // Entity object
        Object entity = null;

        // Map to hold property-name=>foreign-entity relations
        // Map<String, Set<String>> foreignKeysMap = new HashMap<String,
        // Set<String>>();

        try
        {
            entity = entityClass.newInstance();

            // Populate primary key column
            Object rowKey = document.get("_id");
            Class<?> rowKeyValueClass = rowKey.getClass();

            Class<?> idClass = m.getIdAttribute().getJavaType();

            Map<String, Object> relationValue = null;

            rowKey = populateValue(rowKey, idClass);

            rowKey = getTranslatedObject(rowKey, rowKeyValueClass, idClass);
            PropertyAccessorHelper.setId(entity, m, rowKey);

            // Populate entity columns
            // List<Column> columns = m.getColumnsAsList();
            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    m.getPersistenceUnit());
            EntityType entityType = metaModel.entity(entityClass);

            Set<Attribute> columns = entityType.getAttributes();

            for (Attribute column : columns)
            {
                String fieldName = ((AbstractAttribute) column).getJPAColumnName();

                Class javaType = ((AbstractAttribute) column).getBindableJavaType();
                if (metaModel.isEmbeddable(javaType))
                {
                    onViaEmbeddable(entityType, column, m, entity, metaModel.embeddable(javaType), document);
                }
                else if (!column.isAssociation())
                {
                    setColumnValue(document, entity, column);
                }
                else if (relations != null)
                {
                    if (relationValue == null)
                    {
                        relationValue = new HashMap<String, Object>();
                    }

                    if (relations.contains(fieldName)
                            && !fieldName.equals(((AbstractAttribute) m.getIdAttribute()).getJPAColumnName()))
                    {
                        Object colValue = document.get(fieldName);
                        if (colValue != null)
                        {
                           String colFieldName = m.getFieldName(fieldName);
                            Attribute attribute = colFieldName != null ? entityType.getAttribute(colFieldName) : null;
                            EntityMetadata relationMetadata = KunderaMetadataManager.getEntityMetadata(attribute
                                    .getJavaType());
                            colValue = getTranslatedObject(colValue, colValue.getClass(), relationMetadata
                                    .getIdAttribute().getJavaType());
                        }
                        relationValue.put(fieldName, colValue);
                    }

                }
            }

            if (relationValue != null && !relationValue.isEmpty())
            {
                EnhanceEntity e = new EnhanceEntity(entity, PropertyAccessorHelper.getId(entity, m), relationValue);
                return e;
            }
            else
            {
                return entity;
            }

        }
        catch (InstantiationException e)
        {
            log.error("Error while instantiating " + entityClass + ". Details:" + e.getMessage());
            return entity;
        }
        catch (IllegalAccessException e)
        {
            log.error("Error while Getting entity from Document. Details:" + e.getMessage());
            return entity;
        }
        catch (PropertyAccessException e)
        {
            log.error("Error while Getting entity from Document. Details:" + e.getMessage());
            return entity;
        }

    }

    /**
     * @param value
     * @param sourceClass
     * @param targetClass
     * @return
     */
    private Object getTranslatedObject(Object value, Class<?> sourceClass, Class<?> targetClass)
    {
        if (sourceClass.isAssignableFrom(Date.class))
        {
            value = PropertyAccessorHelper.fromDate(targetClass, sourceClass, value);
        }
        else
        {
            value = PropertyAccessorHelper.fromSourceToTargetClass(targetClass, sourceClass, value);
        }
        return value;
    }

    /**
     * Setter for column value, by default converted from string value, in case
     * of map it is automatically converted into map using BasicDBObject.
     * 
     * @param document
     *            mongo document
     * @param entity
     *            searched entity.
     * @param column
     *            column field.
     */
    private void setColumnValue(DBObject document, Object entity, Attribute column)
    {
        Object value = document.get(((AbstractAttribute) column).getJPAColumnName());
        if (value != null)
        {
            if (column.getJavaType().isAssignableFrom(Map.class))
            {
                PropertyAccessorHelper.set(entity, (Field) column.getJavaMember(), ((BasicDBObject) value).toMap());
            }
            else
            {
                value = populateValue(value, value.getClass());
                value = getTranslatedObject(value, value.getClass(), column.getJavaType());
                PropertyAccessorHelper.set(entity, (Field) column.getJavaMember(), value);
            }
        }
    }

    /**
     * Gets the document from entity.
     * 
     * @param m
     *            the m
     * @param entity
     *            the entity
     * @param relations
     *            the relations
     * @return the document from entity
     * @throws PropertyAccessException
     *             the property access exception
     */
    DBObject getDocumentFromEntity(DBObject dbObj, EntityMetadata m, Object entity, List<RelationHolder> relations)
            throws PropertyAccessException
    {
        // List<Column> columns = m.getColumnsAsList();

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());

        // Populate Row Key

        Object id = PropertyAccessorHelper.getId(entity, m);
        dbObj.put("_id",
                id instanceof Calendar ? ((Calendar) id).getTime().toString() : populateValue(id, id.getClass()));

        // Populate columns
        // for (Column column : columns)
        Set<Attribute> columns = entityType.getAttributes();
        for (Attribute column : columns)
        {
            try
            {
                Class javaType = ((AbstractAttribute) column).getBindableJavaType();
                if (metaModel.isEmbeddable(javaType))
                {
                    dbObj = onEmbeddable(entityType, column, m, entity, metaModel.embeddable(javaType), dbObj);
                }
                else if (!column.isAssociation())
                {
                    extractEntityField(entity, dbObj, column);
                }

            }
            catch (PropertyAccessException e1)
            {
                log.error("Can't access property " + column.getName());
            }
        }

        if (relations != null)
        {
            for (RelationHolder rh : relations)
            {
                dbObj.put(rh.getRelationName(), populateValue(rh.getRelationValue(), rh.getRelationValue().getClass()));
            }
        }

        return dbObj;
    }

    /**
     * Extract entity field.
     * 
     * @param entity
     *            the entity
     * @param dbObj
     *            the db obj
     * @param column
     *            the column
     * @throws PropertyAccessException
     *             the property access exception
     */
    private void extractEntityField(Object entity, DBObject dbObj, Attribute column) throws PropertyAccessException
    {
        // A column field may be a collection(not defined as 1-to-M
        // relationship)
        if (column.getJavaType().isAssignableFrom(List.class) || column.getJavaType().isAssignableFrom(Set.class))
        {
            Collection collection = (Collection) PropertyAccessorHelper.getObject(entity,
                    (Field) column.getJavaMember());
            BasicDBList basicDBList = new BasicDBList();
            for (Object o : collection)
            {
                basicDBList.add(o);
            }
            dbObj.put(((AbstractAttribute) column).getJPAColumnName(), basicDBList);

        }
        else if (column.getJavaType().isAssignableFrom(Map.class))
        {
            Map mapObj = (Map) PropertyAccessorHelper.getObject(entity, (Field) column.getJavaMember());
            BasicDBObjectBuilder builder = BasicDBObjectBuilder.start(mapObj);
            dbObj.put(((AbstractAttribute) column).getJPAColumnName(), builder.get());
        }
        else
        {
            // TODO : this should have been handled by DocumentObjectMapper.
            Object valObj = PropertyAccessorHelper.getObject(entity, (Field) column.getJavaMember());
            if (valObj != null)
            {

                dbObj.put(
                        ((AbstractAttribute) column).getJPAColumnName(),
                        valObj instanceof Calendar ? ((Calendar) valObj).getTime().toString() : populateValue(valObj,
                                column.getJavaType()));
            }
        }
    }

    /**
     * @param valObj
     * @return
     */
    Object populateValue(Object valObj, Class clazz)
    {
        if (isUTF8Value(clazz))
        {
            return valObj.toString();
        }
        return valObj;
    }

    private boolean isUTF8Value(Class<?> clazz)
    {
        return (clazz.isAssignableFrom(BigDecimal.class))
                || (clazz.isAssignableFrom(BigInteger.class) || (clazz.isAssignableFrom(String.class))
                        || (clazz.isAssignableFrom(Calendar.class)) || (clazz.isAssignableFrom(GregorianCalendar.class)));
    }

    /**
     * Returns column name from the filter property which is in the form
     * dbName.columnName
     * 
     * @param filterProperty
     *            the filter property
     * @return the column name
     */
    private String getColumnName(String filterProperty)
    {
        StringTokenizer st = new StringTokenizer(filterProperty, ".");
        String columnName = "";
        while (st.hasMoreTokens())
        {
            columnName = st.nextToken();
        }

        return columnName;
    }

    /**
     * Retrieves A collection of embedded object within a document that match a
     * criteria specified in <code>query</code> TODO: This code requires a
     * serious overhawl. Currently it assumes that user query is in the form
     * "Select alias.columnName from EntityName alias". However, correct query
     * to be supported is
     * "Select alias.superColumnName.columnName from EntityName alias"
     * 
     * @param dbCollection
     *            the db collection
     * @param m
     *            the m
     * @param documentName
     *            the document name
     * @param mongoQuery
     *            the mongo query
     * @param result
     *            the result
     * @param orderBy
     *            the order by
     * @return the embedded object list
     * @throws PropertyAccessException
     *             the property access exception
     */
    List getEmbeddedObjectList(DBCollection dbCollection, EntityMetadata m, String documentName,
            BasicDBObject mongoQuery, String result, BasicDBObject orderBy) throws PropertyAccessException
    {
        List list = new ArrayList();// List of embedded object to be returned

        // MongoDBQuery mongoDBQuery = (MongoDBQuery) query;

        // Specified after entity alias in query
        String columnName = getColumnName(result);

        // Something user didn't specify and we have to derive
        // TODO: User must specify this in query and remove this logic once
        // query format is changed
        // String enclosingDocumentName = getEnclosingDocumentName(m,
        // columnName);

        String enclosingDocumentName = null;

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());
        EmbeddableType superColumn = null;
        Set<Attribute> columns = null;
        Attribute attrib = null;
        try
        {
            attrib = entityType.getAttribute(columnName);
            // if (!m.getColumnFieldNames().contains(columnName))
            // {
            Map<String, EmbeddableType> embeddables = metaModel.getEmbeddables(m.getEntityClazz());
            for (String key : embeddables.keySet())
            // for (EmbeddedColumn superColumn : m.getEmbeddedColumnsAsList())
            {
                superColumn = embeddables.get(key);
                // List<Column> columns = superColumn.getColumns();
                columns = superColumn.getAttributes();

                for (Attribute column : columns)
                {
                    if (((AbstractAttribute) column).getJPAColumnName().equals(columnName))
                    {
                        enclosingDocumentName = key;
                        break;
                    }
                }
            }
        }
        catch (IllegalArgumentException iax)
        {
            log.info("No column found for: " + columnName);
        }

        // Query for fetching entities based on user specified criteria
        DBCursor cursor = orderBy != null ? dbCollection.find(mongoQuery).sort(orderBy) : dbCollection.find(mongoQuery);

        // EmbeddableType superColumn =
        // m.getEmbeddedColumn(enclosingDocumentName);

        if (superColumn != null)
        {
            Field superColumnField = (Field) attrib.getJavaMember();
            while (cursor.hasNext())
            {
                DBObject fetchedDocument = cursor.next();
                Object embeddedDocumentObject = fetchedDocument.get(superColumnField.getName());

                if (embeddedDocumentObject != null)
                {
                    if (embeddedDocumentObject instanceof BasicDBList)
                    {
                        Class embeddedObjectClass = PropertyAccessorHelper.getGenericClass(superColumnField);
                        for (Object dbObj : (BasicDBList) embeddedDocumentObject)
                        {
                            Object embeddedObject = new DocumentObjectMapper().getObjectFromDocument(
                                    (BasicDBObject) dbObj, embeddedObjectClass, superColumn.getAttributes());
                            Object fieldValue = PropertyAccessorHelper.getObject(embeddedObject, columnName);

                        }

                    }
                    else if (embeddedDocumentObject instanceof BasicDBObject)
                    {
                        Object embeddedObject = DocumentObjectMapper.getObjectFromDocument(
                                (BasicDBObject) embeddedDocumentObject, superColumn.getJavaType(),
                                superColumn.getAttributes());
                        list.add(embeddedObject);

                    }
                    else
                    {
                        throw new PersistenceException("Can't retrieve embedded object from MONGODB document coz "
                                + "it wasn't stored as BasicDBObject, possible problem in format.");
                    }
                }

            }
        }
        return list;
    }

    /**
     * @param entityType
     * @param column
     * @param m
     * @param entity
     */
    private DBObject onEmbeddable(EntityType entityType, Attribute column, EntityMetadata m, Object entity,
            EmbeddableType embeddableType, DBObject dbObj)
    {

        Object embeddedObject = PropertyAccessorHelper.getObject(entity, (Field) column.getJavaMember());

        if (column.isCollection())
        {
            Collection embeddedCollection = (Collection) embeddedObject;
            // means it is case of element collection
            dbObj.put(
                    ((AbstractAttribute) column).getJPAColumnName(),
                    DocumentObjectMapper.getDocumentListFromCollection(embeddedCollection,
                            embeddableType.getAttributes()));
        }
        else
        {
            dbObj.put(((AbstractAttribute) column).getJPAColumnName(),
                    DocumentObjectMapper.getDocumentFromObject(embeddedObject, embeddableType.getAttributes()));
        }

        return dbObj;
    }

    /**
     * @param entityType
     * @param column
     * @param m
     * @param entity
     * @param embeddable
     * @param document
     */
    private void onViaEmbeddable(EntityType entityType, Attribute column, EntityMetadata m, Object entity,
            EmbeddableType embeddable, DBObject document)
    {
        Field embeddedField = (Field) column.getJavaMember();
        Object embeddedDocumentObject = null;
        // Object embeddedObject = PropertyAccessorHelper.getObject(entity,
        // (Field) column.getJavaMember());
        if (column.isCollection())
        {
            Class embeddedObjectClass = PropertyAccessorHelper.getGenericClass(embeddedField);

            embeddedDocumentObject = document.get(((AbstractAttribute) column).getJPAColumnName());

            Collection embeddedCollection = DocumentObjectMapper.getCollectionFromDocumentList(
                    (BasicDBList) embeddedDocumentObject, embeddedField.getType(), embeddedObjectClass,
                    embeddable.getAttributes());
            PropertyAccessorHelper.set(entity, embeddedField, embeddedCollection);
        }
        else
        {
            embeddedDocumentObject = document.get(((AbstractAttribute) column).getJPAColumnName());
            PropertyAccessorHelper.set(entity, embeddedField, DocumentObjectMapper.getObjectFromDocument(
                    (BasicDBObject) embeddedDocumentObject, ((AbstractAttribute) column).getBindableJavaType(),
                    embeddable.getAttributes()));
        }
    }
}