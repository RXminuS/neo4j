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
package org.neo4j.graphdb;

import org.junit.ClassRule;

import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.kernel.api.impl.labelscan.LuceneLabelScanStoreExtension;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.ImpermanentDatabaseRule;

import static org.neo4j.graphdb.factory.GraphDatabaseSettings.label_scan_store;

public class LuceneLabelScanStoreUpdateIT extends LabelScanStoreUpdateIT
{
    @ClassRule
    public static final DatabaseRule dbRule = new ImpermanentDatabaseRule()
    {
        @Override
        protected void configure( GraphDatabaseBuilder builder )
        {
            builder.setConfig( label_scan_store, LuceneLabelScanStoreExtension.LABEL_SCAN_STORE_NAME );
        }
    };

    @Override
    protected GraphDatabaseService db()
    {
        return dbRule;
    }
}
