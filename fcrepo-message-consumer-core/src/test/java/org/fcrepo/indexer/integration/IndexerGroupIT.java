/**
 * Copyright 2015 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.indexer.integration;

import static java.lang.System.currentTimeMillis;
import static java.util.UUID.randomUUID;
import static org.apache.jena.riot.WebContent.contentTypeN3Alt1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URI;
import javax.inject.Inject;
import javax.ws.rs.core.Link;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.fcrepo.indexer.IndexerGroup;
import org.fcrepo.indexer.TestIndexer;
import org.fcrepo.indexer.system.IndexingIT;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author ajs6f
 * @author Esmé Cowles
 * @since Aug 19, 2013
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration({"/spring-test/test-container.xml"})
public class IndexerGroupIT extends IndexingIT {

    private static final long TIMEOUT = 15000;

    @Inject
    private IndexerGroup indexerGroup;

    @Inject
    private TestIndexer testIndexer;

    private static final Logger LOGGER = getLogger(IndexerGroupIT.class);

    // FIXME: The tests in these suites sometimes fail on windows builds
    //        https://www.pivotaltracker.com/story/show/72709646
    @Test
    public void testIndexerGroupUpdate() throws Exception {
        final String uri = serverAddress + randomUUID();
        createIndexableObject(uri);
        shouldBeIndexed(uri);
    }
    private void createIndexableObject(final String uri) throws Exception {
        final String objectRdf =
            "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ."
                    + "@prefix indexing:<http://fedora.info/definitions/v4/indexing#>."
                    + "<" + uri + ">  rdf:type  <http://fedora.info/definitions/v4/indexing#Indexable> ;"
                    + "indexing:hasIndexingTransformation \"default\".";

        createResource(uri, objectRdf, contentTypeN3Alt1);
        LOGGER.debug("Created object at: {}", uri);
    }
    private HttpResponse createResource(final String uri, final String content, final String contentType)
            throws Exception {
        final HttpPut createRequest = new HttpPut(uri);
        createRequest.setEntity(new StringEntity(content));
        createRequest.addHeader("Content-Type", contentType);
        final HttpResponse response = client.execute(createRequest);
        assertEquals(201, response.getStatusLine().getStatusCode());
        return response;
    }
    private void shouldBeIndexed(final String uri) throws Exception {
        final Long start = currentTimeMillis();
        synchronized (testIndexer) {
            while (!testIndexer.receivedUpdate(new URI(uri)) && (currentTimeMillis() - start < TIMEOUT)) {
                LOGGER.debug("Waiting for next notification from TestIndexer...");
                testIndexer.wait(1000);
            }
        }
        assertTrue("Test indexer should have received an update message for " + uri + "!",
                testIndexer.receivedUpdate(new URI(uri)));
        LOGGER.debug("Received update at test indexer for identifier: {}", uri);
    }

    @Test
    public void testIndexerGroupDelete() throws Exception {

        final String uri = serverAddress + "removeTestPid";
        createIndexableObject(uri);

        // delete dummy object
        final HttpDelete method = new HttpDelete(uri);
        final HttpResponse response = client.execute(method);
        assertEquals(204, response.getStatusLine().getStatusCode());
        LOGGER.debug("Deleted object at: {}", uri);

        final Long start = currentTimeMillis();
        synchronized (testIndexer) {
            while (!testIndexer.receivedRemove(new URI(uri))
                    && (currentTimeMillis() - start < TIMEOUT)) {
                LOGGER.debug("Waiting for next notification from TestIndexer...");
                testIndexer.wait(1000);
            }
        }
        assertTrue("Test indexer should have received remove message for " + uri + "!", testIndexer
                .receivedRemove(new URI(uri)));
        LOGGER.debug("Received remove at test indexer for identifier: {}", uri);



    }

    @Test
    public void testIndexerGroupReindex() throws Exception {
        // create sample records
        final String[] pids = { "a1", "a1/b1", "a1/b2", "a1/b1/c1" };
        for ( String pid : pids ) {
            createIndexableObject(serverAddress + pid);
        }
        shouldBeIndexed(serverAddress + "a1/b1/c1");

        // clear test indexer lists of updated records
        testIndexer.clear();

        // reindex everything
        indexerGroup.reindex(new URI(serverAddress), true);

        // records should be reindexed
        synchronized (testIndexer) {
            for ( String pid : pids ) {
                final String uri = serverAddress + pid;
                final Long start = currentTimeMillis();
                while (!testIndexer.receivedUpdate(new URI(uri)) && (currentTimeMillis() - start < TIMEOUT)) {
                    LOGGER.debug("Waiting for " + uri);
                    testIndexer.wait(1000);
                }

                assertTrue("Record should have been reindexed: " + uri,
                        testIndexer.receivedUpdate(new URI(uri)));
            }
        }
    }

    @Test
    public void testDatastreamIndexing() throws Exception {
        // create object with datastream
        final String uri = serverAddress + randomUUID();
        createIndexableObject(uri);
        final HttpResponse response = createResource(uri + "/ds1", "test datastream content", "text/plain");

        // make datastream indexable
        final URI descURI = Link.valueOf(response.getFirstHeader("Link").getValue()).getUri();
        final HttpPatch patch = new HttpPatch(descURI);
        final String sparqlUpdate = "insert { <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                + "<http://fedora.info/definitions/v4/indexing#Indexable> } where {}";
        patch.setEntity(new StringEntity(sparqlUpdate));
        patch.addHeader("Content-Type", "application/sparql-update");
        assertEquals(204, client.execute(patch).getStatusLine().getStatusCode());

        // make sure it was indexed
        shouldBeIndexed(uri + "/ds1");
    }

}
