package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.sql.SQLException;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ProjectResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
            @Override
            protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Repository repository;
    @Mock Indexer indexer;

    @Test
    public void test_get_project() {
        get("/api/project/id/projectId").should().respond(200).contain("projectId");
    }

    @Test
    public void test_delete_project() throws SQLException {
        when(repository.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/id/local-datashare").should().respond(204);
        delete("/api/project/id/local-datashare").should().respond(404);
    }

    @Test
    public void test_delete_project_only_delete_index() throws Exception {
        when(repository.deleteAll("local-datashare")).thenReturn(false).thenReturn(false);
        when(indexer.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/id/local-datashare").should().respond(204);
        delete("/api/project/id/local-datashare").should().respond(404);
    }

    @Test
    public void test_delete_project_with_unauthorized_user() throws SQLException {
        when(repository.deleteAll("projectId")).thenReturn(true);
        delete("/api/project/id/hacker-datashare").withPreemptiveAuthentication("hacker", "pass").should().respond(401);
        delete("/api/project/id/projectId").should().respond(401);
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new ProjectResource(repository, indexer)).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Override
    public int port() { return server.port();}
}