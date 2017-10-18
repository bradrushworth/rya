/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.indexing.pcj.fluo.integration;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.apache.accumulo.core.client.Connector;
import org.apache.fluo.api.client.FluoClient;
import org.apache.fluo.api.client.FluoFactory;
import org.apache.rya.accumulo.AccumuloRyaDAO;
import org.apache.rya.api.utils.CloseableIterator;
import org.apache.rya.indexing.external.PrecomputedJoinIndexer;
import org.apache.rya.indexing.pcj.fluo.api.CreateFluoPcj;
import org.apache.rya.indexing.pcj.storage.PrecomputedJoinStorage;
import org.apache.rya.indexing.pcj.storage.accumulo.AccumuloPcjStorage;
import org.apache.rya.indexing.pcj.update.PrecomputedJoinUpdater;
import org.apache.rya.pcj.fluo.test.base.RyaExportITBase;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.junit.Test;

import com.google.common.collect.Sets;

/**
 * This test ensures that the correct updates are pushed by Fluo to the external PCJ table as triples are added to Rya
 * through the {@link RepositoryConnection}.  The key difference between these tests and those in {@link InputIT} is
 * that streaming triples are added through the RepositoryConnection and not through the {@link FluoClient}.  These
 * tests are designed to verify that the {@link AccumuloRyaDAO} has been integrated with the {@link PrecomputedJoinIndexer}
 * and that the associated {@link PrecomputedJoinUpdater} updates Fluo accordingly.
 */
public class RyaInputIncrementalUpdateIT extends RyaExportITBase {

    /**
     * Ensure historic matches are included in the result.
     */
    @Test
    public void streamResultsThroughRya() throws Exception {
        // A query that finds people who talk to Eve and work at Chipotle.
        final String sparql =
                "SELECT ?x " + "WHERE { " +
                    "?x <http://talksTo> <http://Eve>. " +
                    "?x <http://worksAt> <http://Chipotle>." +
                "}";

        // Triples that are loaded into Rya before the PCJ is created.
        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Set<Statement> historicTriples = Sets.newHashSet(
                vf.createStatement(vf.createIRI("http://Alice"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Bob"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Charlie"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),

                vf.createStatement(vf.createIRI("http://Eve"), vf.createIRI("http://helps"), vf.createIRI("http://Kevin")),

                vf.createStatement(vf.createIRI("http://Bob"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")),
                vf.createStatement(vf.createIRI("http://Charlie"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")),
                vf.createStatement(vf.createIRI("http://Eve"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")),
                vf.createStatement(vf.createIRI("http://David"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")));

        // The expected results of the SPARQL query once the PCJ has been
        // computed.
        final Set<BindingSet> expected = new HashSet<>();

        MapBindingSet bs = new MapBindingSet();
        bs.addBinding("x", vf.createIRI("http://Bob"));
        expected.add(bs);

        bs = new MapBindingSet();
        bs.addBinding("x", vf.createIRI("http://Charlie"));
        expected.add(bs);

        // Create the PCJ table.
        final Connector accumuloConn = super.getAccumuloConnector();
        final PrecomputedJoinStorage pcjStorage = new AccumuloPcjStorage(accumuloConn, getRyaInstanceName());
        final String pcjId = pcjStorage.createPcj(sparql);

        try(FluoClient fluoClient = FluoFactory.newClient(super.getFluoConfiguration())) {
            // Tell the Fluo app to maintain the PCJ.
            new CreateFluoPcj().withRyaIntegration(pcjId, pcjStorage, fluoClient, accumuloConn, getRyaInstanceName());

            // Verify the end results of the query match the expected results.
            super.getMiniFluo().waitForObservers();

            // Load the historic data into Rya.
            final SailRepositoryConnection ryaConn = super.getRyaSailRepository().getConnection();
            for (final Statement triple : historicTriples) {
                ryaConn.add(triple);
            }

            super.getMiniFluo().waitForObservers();

            final Set<BindingSet> results = new HashSet<>();
            try(CloseableIterator<BindingSet> resultIt = pcjStorage.listResults(pcjId)) {
                while(resultIt.hasNext()) {
                    results.add( resultIt.next() );
                }
            }

            assertEquals(expected, results);
        }
    }

    /**
     * Simulates the case where a Triple is added to Rya, a new query that
     * includes that triple as a historic match is inserted into Fluo, and then
     * some new triple that matches the query is streamed into Fluo. The query's
     * results must include both the historic result and the newly streamed
     * result.
     */
    @Test
    public void historicThenStreamedResults() throws Exception {
        // A query that finds people who talk to Eve and work at Chipotle.
        final String sparql =
                "SELECT ?x " + "WHERE { " +
                    "?x <http://talksTo> <http://Eve>. " +
                    "?x <http://worksAt> <http://Chipotle>." +
                "}";

        // Triples that are loaded into Rya before the PCJ is created.
        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Set<Statement> historicTriples = Sets.newHashSet(
                vf.createStatement(vf.createIRI("http://Alice"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Alice"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")),
                vf.createStatement(vf.createIRI("http://Joe"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")));

        // Triples that will be streamed into Fluo after the PCJ has been
        final Set<Statement> streamedTriples = Sets.newHashSet(
                vf.createStatement(vf.createIRI("http://Frank"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Joe"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Frank"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")));

        // Load the historic data into Rya.
        final SailRepositoryConnection ryaConn = super.getRyaSailRepository().getConnection();
        for (final Statement triple : historicTriples) {
            ryaConn.add(triple);
        }

        // Create the PCJ table.
        final Connector accumuloConn = super.getAccumuloConnector();
        final PrecomputedJoinStorage pcjStorage = new AccumuloPcjStorage(accumuloConn, getRyaInstanceName());
        final String pcjId = pcjStorage.createPcj(sparql);

        try(FluoClient fluoClient = FluoFactory.newClient(super.getFluoConfiguration())) {
            // Tell the Fluo app to maintain the PCJ.
            new CreateFluoPcj().withRyaIntegration(pcjId, pcjStorage, fluoClient, accumuloConn, getRyaInstanceName());

            super.getMiniFluo().waitForObservers();

            // Load the streaming data into Rya.
            for (final Statement triple : streamedTriples) {
                ryaConn.add(triple);
            }

            // Ensure Alice is a match.
            super.getMiniFluo().waitForObservers();

            final Set<BindingSet> expected = new HashSet<>();
            MapBindingSet bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Alice"));
            expected.add(bs);

            bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Frank"));
            expected.add(bs);

            bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Joe"));
            expected.add(bs);

            final Set<BindingSet> results = new HashSet<>();
            try(CloseableIterator<BindingSet> resultIt = pcjStorage.listResults(pcjId)) {
                while(resultIt.hasNext()) {
                    results.add( resultIt.next() );
                }
            }

            assertEquals(expected, results);
        }
    }

    @Test
    public void historicAndStreamMultiVariables() throws Exception {
        // A query that finds people who talk to other people and work at Chipotle.
        final String sparql =
                "SELECT ?x ?y " + "WHERE { " +
                    "?x <http://talksTo> ?y. " +
                    "?x <http://worksAt> <http://Chipotle>." +
                 "}";

        // Triples that are loaded into Rya before the PCJ is created.
        final ValueFactory vf = SimpleValueFactory.getInstance();
        final Set<Statement> historicTriples = Sets.newHashSet(
                vf.createStatement(vf.createIRI("http://Alice"), vf.createIRI("http://talksTo"), vf.createIRI("http://Eve")),
                vf.createStatement(vf.createIRI("http://Alice"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")),
                vf.createStatement(vf.createIRI("http://Joe"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")));

        // Triples that will be streamed into Fluo after the PCJ has been
        final Set<Statement> streamedTriples = Sets.newHashSet(
                vf.createStatement(vf.createIRI("http://Frank"), vf.createIRI("http://talksTo"), vf.createIRI("http://Betty")),
                vf.createStatement(vf.createIRI("http://Joe"), vf.createIRI("http://talksTo"), vf.createIRI("http://Alice")),
                vf.createStatement(vf.createIRI("http://Frank"), vf.createIRI("http://worksAt"), vf.createIRI("http://Chipotle")));

        // Load the historic data into Rya.
        final SailRepositoryConnection ryaConn = super.getRyaSailRepository().getConnection();
        for (final Statement triple : historicTriples) {
            ryaConn.add(triple);
        }

        // Create the PCJ table.
        final Connector accumuloConn = super.getAccumuloConnector();
        final PrecomputedJoinStorage pcjStorage = new AccumuloPcjStorage(accumuloConn, getRyaInstanceName());
        final String pcjId = pcjStorage.createPcj(sparql);

        try(FluoClient fluoClient = FluoFactory.newClient(super.getFluoConfiguration())) {
            // Tell the Fluo app to maintain the PCJ.
            new CreateFluoPcj().withRyaIntegration(pcjId, pcjStorage, fluoClient, accumuloConn, getRyaInstanceName());

            super.getMiniFluo().waitForObservers();

            // Load the streaming data into Rya.
            for (final Statement triple : streamedTriples) {
                ryaConn.add(triple);
            }

            // Ensure Alice is a match.
            super.getMiniFluo().waitForObservers();

            final Set<BindingSet> expected = new HashSet<>();

            MapBindingSet bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Alice"));
            bs.addBinding("y", vf.createIRI("http://Eve"));
            expected.add(bs);

            bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Frank"));
            bs.addBinding("y", vf.createIRI("http://Betty"));
            expected.add(bs);

            bs = new MapBindingSet();
            bs.addBinding("x", vf.createIRI("http://Joe"));
            bs.addBinding("y", vf.createIRI("http://Alice"));
            expected.add(bs);

            final Set<BindingSet> results = new HashSet<>();
            try(CloseableIterator<BindingSet> resultIt = pcjStorage.listResults(pcjId)) {
                while(resultIt.hasNext()) {
                    results.add( resultIt.next() );
                }
            }

            assertEquals(expected, results);
        }
    }
}