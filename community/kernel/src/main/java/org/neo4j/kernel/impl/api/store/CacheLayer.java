/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.store;

import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Predicate;

import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.function.Predicates;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.schema.NodePropertyDescriptor;
import org.neo4j.kernel.api.schema.RelationshipPropertyDescriptor;
import org.neo4j.kernel.api.constraints.NodePropertyConstraint;
import org.neo4j.kernel.api.constraints.PropertyConstraint;
import org.neo4j.kernel.api.constraints.RelationshipPropertyConstraint;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.schema.IndexDescriptor;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.schema_new.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema_new.SchemaDescriptorFactory;
import org.neo4j.kernel.api.schema_new.SchemaDescriptorPredicates;
import org.neo4j.kernel.api.schema_new.index.IndexBoundary;
import org.neo4j.kernel.impl.api.RelationshipVisitor;
import org.neo4j.kernel.impl.store.record.IndexRule;
import org.neo4j.register.Register.DoubleLongRegister;
import org.neo4j.storageengine.api.StorageProperty;
import org.neo4j.storageengine.api.StorageStatement;
import org.neo4j.storageengine.api.StoreReadLayer;
import org.neo4j.storageengine.api.Token;
import org.neo4j.storageengine.api.schema.PopulationProgress;

/**
 * This is the object-caching layer. It delegates to the legacy object cache system if possible, or delegates to the
 * disk layer if there is no relevant caching.
 * <p/>
 * An important consideration when working on this is that there are plans to remove the object cache, which means that
 * the aim for this layer is to disappear.
 */
public class CacheLayer implements StoreReadLayer
{
    private static final Function<? super IndexRule, IndexDescriptor> TO_INDEX_RULE =
            rule -> IndexBoundary.map( rule.getIndexDescriptor() );

    private final SchemaCache schemaCache;
    private final DiskLayer diskLayer;

    public CacheLayer( DiskLayer diskLayer, SchemaCache schemaCache )
    {
        this.diskLayer = diskLayer;
        this.schemaCache = schemaCache;
    }

    @Override
    public StorageStatement newStatement()
    {
        return diskLayer.newStatement();
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( int labelId )
    {
        return toIndexDescriptors(
                Iterables.filter(
                        rule -> SchemaDescriptorPredicates.hasLabel( rule, labelId ) && !rule.canSupportUniqueConstraint(),
                        schemaCache.indexRules()
                ) );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll()
    {
        return toIndexDescriptors( Iterables.filter( rule -> !rule.canSupportUniqueConstraint(), schemaCache.indexRules() ) );
    }

    @Override
    public Iterator<IndexDescriptor> uniquenessIndexesGetForLabel( int labelId )
    {
        return toIndexDescriptors(
                    Iterables.filter(
                        rule -> SchemaDescriptorPredicates.hasLabel( rule, labelId ) && rule.canSupportUniqueConstraint(),
                        schemaCache.indexRules()
                    ) );
    }

    @Override
    public Iterator<IndexDescriptor> uniquenessIndexesGetAll()
    {
        return toIndexDescriptors( Iterables.filter( IndexRule::canSupportUniqueConstraint, schemaCache.indexRules() ) );
    }

    private static Iterator<IndexDescriptor> toIndexDescriptors( Iterable<IndexRule> rules )
    {
        return Iterators.map( TO_INDEX_RULE, rules.iterator() );
    }

    @Override
    public Long indexGetOwningUniquenessConstraintId( IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        IndexRule rule = indexRule( index, Predicates.alwaysTrue() );
        if ( rule != null )
        {
            return rule.getOwningConstraint();
        }
        return diskLayer.indexGetOwningUniquenessConstraintId( index );
    }

    @Override
    public long indexGetCommittedId( IndexDescriptor index, Predicate<IndexRule> filter )
            throws SchemaRuleNotFoundException
    {
        IndexRule rule = indexRule( index, filter );
        if ( rule != null )
        {
            return rule.getId();
        }
        return diskLayer.indexGetCommittedId( index, filter );
    }

    @Override
    public IndexRule indexRule( IndexDescriptor index, Predicate<IndexRule> filter )
    {
        LabelSchemaDescriptor schemaDescription =
                SchemaDescriptorFactory.forLabel( index.getLabelId(), index.getPropertyKeyId() );

        for ( IndexRule rule : schemaCache.indexRules() )
        {
            if ( filter.test( rule ) && rule.getSchemaDescriptor().equals( schemaDescription ) )
            {
                return rule;
            }
        }
        return null;
    }

    @Override
    public PrimitiveIntIterator graphGetPropertyKeys()
    {
        return diskLayer.graphGetPropertyKeys();
    }

    @Override
    public Object graphGetProperty( int propertyKeyId )
    {
        return diskLayer.graphGetProperty( propertyKeyId );
    }

    @Override
    public Iterator<StorageProperty> graphGetAllProperties()
    {
        return diskLayer.graphGetAllProperties();
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabelAndPropertyKey( NodePropertyDescriptor descriptor )
    {
        return schemaCache.constraintsForLabelAndProperty( descriptor );
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabel( int labelId )
    {
        return schemaCache.constraintsForLabel( labelId );
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipTypeAndPropertyKey(
            RelationshipPropertyDescriptor descriptor )
    {
        return schemaCache.constraintsForRelationshipTypeAndProperty( descriptor );
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipType( int typeId )
    {
        return schemaCache.constraintsForRelationshipType( typeId );
    }

    @Override
    public Iterator<PropertyConstraint> constraintsGetAll()
    {
        return schemaCache.constraints();
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( StorageStatement state, int labelId )
    {
        return diskLayer.nodesGetForLabel( state, labelId );
    }

    @Override
    public IndexDescriptor indexGetForLabelAndPropertyKey( NodePropertyDescriptor descriptor )
    {
        return schemaCache.indexDescriptor( descriptor );
    }

    @Override
    public InternalIndexState indexGetState( IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return diskLayer.indexGetState( descriptor );
    }

    @Override
    public PopulationProgress indexGetPopulationProgress( IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return diskLayer.indexGetPopulationProgress( descriptor );
    }

    @Override
    public long indexSize( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return diskLayer.indexSize( descriptor );
    }

    @Override
    public double indexUniqueValuesPercentage( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return diskLayer.indexUniqueValuesPercentage( descriptor );
    }

    @Override
    public String indexGetFailure( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return diskLayer.indexGetFailure( descriptor );
    }

    @Override
    public int labelGetForName( String labelName )
    {
        return diskLayer.labelGetForName( labelName );
    }

    @Override
    public String labelGetName( int labelId ) throws LabelNotFoundKernelException
    {
        return diskLayer.labelGetName( labelId );
    }

    @Override
    public int propertyKeyGetForName( String propertyKeyName )
    {
        return diskLayer.propertyKeyGetForName( propertyKeyName );
    }

    @Override
    public int propertyKeyGetOrCreateForName( String propertyKeyName )
    {
        return diskLayer.propertyKeyGetOrCreateForName( propertyKeyName );
    }

    @Override
    public String propertyKeyGetName( int propertyKeyId ) throws PropertyKeyIdNotFoundKernelException
    {
        return diskLayer.propertyKeyGetName( propertyKeyId );
    }

    @Override
    public Iterator<Token> propertyKeyGetAllTokens()
    {
        return diskLayer.propertyKeyGetAllTokens();
    }

    @Override
    public Iterator<Token> labelsGetAllTokens()
    {
        return diskLayer.labelsGetAllTokens();
    }

    @Override
    public Iterator<Token> relationshipTypeGetAllTokens()
    {
        return diskLayer.relationshipTypeGetAllTokens();
    }

    @Override
    public int relationshipTypeGetForName( String relationshipTypeName )
    {
        return diskLayer.relationshipTypeGetForName( relationshipTypeName );
    }

    @Override
    public String relationshipTypeGetName( int relationshipTypeId ) throws RelationshipTypeIdNotFoundKernelException
    {
        return diskLayer.relationshipTypeGetName( relationshipTypeId );
    }

    @Override
    public int labelGetOrCreateForName( String labelName ) throws TooManyLabelsException
    {
        return diskLayer.labelGetOrCreateForName( labelName );
    }

    @Override
    public int relationshipTypeGetOrCreateForName( String relationshipTypeName )
    {
        return diskLayer.relationshipTypeGetOrCreateForName( relationshipTypeName );
    }

    @Override
    public <EXCEPTION extends Exception> void relationshipVisit( long relationshipId,
            RelationshipVisitor<EXCEPTION> relationshipVisitor ) throws EntityNotFoundException, EXCEPTION
    {
        diskLayer.relationshipVisit( relationshipId, relationshipVisitor );
    }

    @Override
    public long countsForNode( int labelId )
    {
        return diskLayer.countsForNode( labelId );
    }

    @Override
    public long countsForRelationship( int startLabelId, int typeId, int endLabelId )
    {
        return diskLayer.countsForRelationship( startLabelId, typeId, endLabelId );
    }

    @Override
    public PrimitiveLongIterator nodesGetAll()
    {
        return diskLayer.nodesGetAll();
    }

    @Override
    public RelationshipIterator relationshipsGetAll()
    {
        return diskLayer.relationshipsGetAll();
    }

    @Override
    public long reserveNode()
    {
        return diskLayer.reserveNode();
    }

    @Override
    public long reserveRelationship()
    {
        return diskLayer.reserveRelationship();
    }

    @Override
    public void releaseNode( long id )
    {
        diskLayer.releaseNode( id );
    }

    @Override
    public void releaseRelationship( long id )
    {
        diskLayer.releaseRelationship( id );
    }

    @Override
    public long nodesGetCount()
    {
        return diskLayer.nodesGetCount();
    }

    @Override
    public long relationshipsGetCount()
    {
        return diskLayer.relationshipsGetCount();
    }

    @Override
    public int labelCount()
    {
        return diskLayer.labelCount();
    }

    @Override
    public int propertyKeyCount()
    {
        return diskLayer.propertyKeyCount();
    }

    @Override
    public int relationshipTypeCount()
    {
        return diskLayer.relationshipTypeCount();
    }

    @Override
    public DoubleLongRegister indexUpdatesAndSize( IndexDescriptor index, DoubleLongRegister target )
    {
        return diskLayer.indexUpdatesAndSize( index, target );
    }

    @Override
    public DoubleLongRegister indexSample( IndexDescriptor index, DoubleLongRegister target )
    {
        return diskLayer.indexSample( index, target );
    }

    @Override
    public boolean nodeExists( long id )
    {
        return diskLayer.nodeExists( id );
    }
}
