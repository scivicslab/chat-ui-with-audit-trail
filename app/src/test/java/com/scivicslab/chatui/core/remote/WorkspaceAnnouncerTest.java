package com.scivicslab.chatui.core.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chat-ui instance announces itself to the AI-workspace named in {@code AI_WORKSPACE_NODE}, with
 * {@code announceNode} on {@code ai-workspace-remote}: every few seconds until it is accepted, then every 60 seconds
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 6; decision 4, question 11).
 *
 * <p>The AI-workspace is a stub: its HTTP lists, and a real node whose gateway records what it is told and refuses
 * until it is told to accept, as AI-workspace does while the instance is not yet READY.</p>
 */
class WorkspaceAnnouncerTest {

    @TempDir
    Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

    static final class StubWorkspace {
        final List<String> told = new CopyOnWriteArrayList<>();
        volatile boolean accepting;
    }

    static final class StubGatewayIIAR extends IIActorRef<StubWorkspace> {
        StubGatewayIIAR(StubWorkspace w, IIActorSystem s) { super("ai-workspace-remote", w, s); }
        @Action("announceNode")
        public ActionResult announceNode(String args) {
            object.told.add(args);
            return new ActionResult(true, object.accepting ? "{\"accepted\":true}"
                    : "{\"accepted\":false,\"reason\":\"the instance on port 18410 is STARTING, not READY\"}");
        }
    }

    private DistributedIIActorSystem workspaceNode;
    private HttpServer workspaceHttp;
    private RemoteNodes remote;
    private StubWorkspace stub;
    private int myActorPort;
    private final AtomicLong now = new AtomicLong(1_000_000);

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        int wsPort = freePort();
        workspaceNode = DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo("ai-workspace-test", "127.0.0.1", wsPort), List.of()))
                .allowedActions(Set.of(new AllowedAction("ai-workspace-remote", "announceNode")))
                .noDeduplication().build();
        stub = new StubWorkspace();
        workspaceNode.addIIActor(new StubGatewayIIAR(stub, workspaceNode));
        workspaceNode.startHttpServer("127.0.0.1", wsPort);

        String nodes = "[{\"name\":\"ai-workspace-test\",\"host\":\"127.0.0.1\",\"actorPort\":" + wsPort + ",\"kind\":\"ai-workspace\"}]";
        workspaceHttp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workspaceHttp.createContext("/", ex -> {
            String body = ex.getRequestURI().getPath().equals("/api/nodes") ? nodes : "[]";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var out = ex.getResponseBody()) { out.write(b); }
        });
        workspaceHttp.start();

        myActorPort = freePort();
        remote = RemoteNodes.start(new IIActorSystem("announcer-test"), "",
                "http://127.0.0.1:" + workspaceHttp.getAddress().getPort(), "chat-ui-18410", myActorPort, dir.resolve("ids.txt"));
    }

    @AfterEach
    void tearDown() {
        if (remote != null) remote.close();
        if (workspaceHttp != null) workspaceHttp.stop(0);
        if (workspaceNode != null) workspaceNode.close();
    }

    private WorkspaceAnnouncer announcer() {
        return new WorkspaceAnnouncer(remote, WorkspaceAnnouncer.parse("ai-workspace-test@127.0.0.1:1").orElseThrow(),
                18410, now::get);
    }

    @Test
    void theWorkspaceNodeIsReadFromAiWorkspaceNode() {
        assertEquals(Optional.of("ai-workspace-28000"), WorkspaceAnnouncer.parse("ai-workspace-28000@127.0.0.1:29000"));
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse(null), "not run by an AI-workspace");
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse(""));
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse("ai-workspace-28000"), "no address");
    }

    @Test
    void itTellsItsNameKindAndBothPorts() throws Exception {
        stub.accepting = true;
        WorkspaceAnnouncer a = announcer();
        assertTrue(a.tick());
        assertTrue(a.accepted());
        JsonNode told = JSON.readTree(stub.told.get(0));
        assertEquals("chat-ui-18410", told.get("name").asText());
        assertEquals("chat-ui", told.get("kind").asText());
        assertEquals(myActorPort, told.get("actorPort").asInt());
        assertEquals(18410, told.get("httpPort").asInt());
    }

    @Test
    void untilItIsAcceptedItTriesEveryFewSeconds_thenEverySixty() {
        WorkspaceAnnouncer a = announcer();
        assertTrue(a.tick());
        assertFalse(a.accepted());
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        stub.accepting = true;
        assertTrue(a.tick());
        assertTrue(a.accepted());
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        assertFalse(a.tick(), "not sent again so soon");
        now.addAndGet(WorkspaceAnnouncer.REANNOUNCE_MILLIS);
        assertTrue(a.tick());
        assertEquals(3, stub.told.size());
    }

    @Test
    void aWorkspaceThatIsDownIsTriedAgain_andNothingIsThrown() {
        workspaceNode.close();
        workspaceNode = null;
        WorkspaceAnnouncer a = announcer();
        assertTrue(a.tick());
        assertFalse(a.accepted());
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        assertTrue(a.tick());
    }

    @Test
    void theAnnouncerDoesNotMakeTheWorkspaceAChatUiPeer() {
        stub.accepting = true;
        announcer().tick();
        assertFalse(remote.others().contains("ai-workspace-test"), remote.others().toString());
    }

    @Test
    void announcingStartsOnlyWithAnAiWorkspaceNode() {
        assertFalse(remote.announceTo(null, 18410), "not run by an AI-workspace");
        assertTrue(remote.announceTo("ai-workspace-test@127.0.0.1:1", 18410));
    }
}
