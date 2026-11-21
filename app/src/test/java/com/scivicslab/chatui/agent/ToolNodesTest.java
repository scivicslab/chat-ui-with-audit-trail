package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.remote.RemoteNodes;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation's {@code search_docs}, {@code list_references} and {@code list_running_tools} reach html-saurus and
 * AI-workspace as nodes of the distributed actor system, found in the AI-workspace's {@code GET /api/nodes}
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 6; decisions 5, 7, 9).
 *
 * <p>Everything runs in this JVM on 127.0.0.1: a stub AI-workspace (its HTTP list and its node), two stub html-saurus
 * nodes, and an HTTP html-saurus that answers what the first node answers, so the two paths can be compared.</p>
 */
class ToolNodesTest {

    @TempDir
    Path dir;

    static final String HITS_FULLTEXT = "[{\"id\":\"ActorTree_261118_oo01\",\"title\":\"Actor tree\",\"pagePath\":\"/proj/docs/actors\","
            + "\"srcPath\":\"/w/proj/docs/actors.md\",\"summary\":\"Every searcher is a child.\"}]";
    static final String HITS_SEMANTIC = "[{\"id\":\"Mailbox_261118_oo01\",\"title\":\"Mailbox\",\"path\":\"/proj/docs/mailbox\","
            + "\"srcPath\":\"/w/proj/docs/mailbox.md\",\"summary\":\"Messages first.\"},"
            + "{\"id\":\"ActorTree_261118_oo01\",\"title\":\"Actor tree\",\"path\":\"/proj/docs/actors\","
            + "\"srcPath\":\"/w/proj/docs/actors.md\",\"summary\":\"Every searcher is a child.\"}]";
    static final String HITS_RELATED = "[{\"id\":\"Scheduler_261118_oo01\",\"title\":\"Scheduler\",\"path\":\"/proj/docs/sched\","
            + "\"srcPath\":\"/w/proj/docs/sched.md\",\"summary\":\"Ticks.\"}]";
    static final String EDGES = "[{\"id\":\"Mailbox_261118_oo01\",\"title\":\"Mailbox\",\"path\":\"/proj/docs/mailbox\","
            + "\"srcPath\":\"/w/proj/docs/mailbox.md\",\"relation\":\"prerequisite\"}]";
    static final String OTHER_HITS = "[{\"id\":\"Other_261118_oo01\",\"title\":\"From the second portal\",\"path\":\"/x\","
            + "\"srcPath\":\"/w/x.md\",\"summary\":\"other\"}]";
    static final String INSTANCES = "[{\"name\":\"html-saurus\",\"port\":28001,\"url\":\"http://localhost:28001/\",\"state\":\"READY\",\"title\":\"\"},"
            + "{\"name\":\"chat-ui-with-audit-trail-cloud-llm\",\"port\":28010,\"url\":\"http://localhost:28010/\",\"state\":\"READY\",\"title\":\"dev\"},"
            + "{\"name\":\"frame-viewer\",\"port\":28011,\"url\":\"http://localhost:28011/\",\"state\":\"STARTING\",\"title\":\"\"}]";

    /** A stub html-saurus portal's gateway: answers fixed hits, records what it was asked. */
    static final class Portal {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final String fullText, semantic, related;
        Portal(String fullText, String semantic, String related) {
            this.fullText = fullText; this.semantic = semantic; this.related = related;
        }
        ActionResult answer(String action, String args, String json) {
            asked.add(action + " " + args);
            return new ActionResult(true, json);
        }
        ActionResult references(String args) {
            asked.add("references " + args);
            if (args.contains("NoSuchDoc")) return new ActionResult(false, "references: not found");
            return new ActionResult(true, args.contains("backward") ? "[]" : EDGES);
        }
    }

    static final class PortalIIAR extends IIActorRef<Portal> {
        PortalIIAR(Portal p, IIActorSystem s) { super("html-saurus-remote", p, s); }
        @Action("search") public ActionResult search(String a) { return object.answer("search", a, object.fullText); }
        @Action("searchSemantic") public ActionResult searchSemantic(String a) { return object.answer("searchSemantic", a, object.semantic); }
        @Action("findRelated") public ActionResult findRelated(String a) { return object.answer("findRelated", a, object.related); }
        @Action("references") public ActionResult references(String a) { return object.references(a); }
    }

    /** A stub AI-workspace's gateway. */
    static final class Workspace {
        final List<String> asked = new CopyOnWriteArrayList<>();
    }

    static final class WorkspaceIIAR extends IIActorRef<Workspace> {
        WorkspaceIIAR(Workspace w, IIActorSystem s) { super("ai-workspace-remote", w, s); }
        @Action("listInstances") public ActionResult listInstances(String a) {
            object.asked.add(a);
            return new ActionResult(true, a.contains("frame-viewer")
                    ? "[{\"name\":\"frame-viewer\",\"port\":28011,\"url\":\"http://localhost:28011/\",\"state\":\"STARTING\",\"title\":\"\"}]"
                    : INSTANCES);
        }
    }

    private final List<AutoCloseable> closing = new ArrayList<>();
    private Portal first, second;
    private Workspace workspace;
    private RemoteNodes remote;
    private String httpBase;
    /** What the stub AI-workspace's HTTP lists answer; a test may change them after the remote path started. */
    private final Map<String, String> listAnswers = new java.util.concurrent.ConcurrentHashMap<>();
    private int firstPort, secondPort, workspacePort;

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private DistributedIIActorSystem node(String name, int port, String gateway, Set<String> actions) throws IOException {
        DistributedIIActorSystem s = DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo(name, "127.0.0.1", port), List.of()))
                .allowedActions(actions.stream().map(a -> new AllowedAction(gateway, a)).collect(Collectors.toSet()))
                .noDeduplication().build();
        closing.add(s);
        return s;
    }

    private HttpServer http(Map<String, String> answers) throws IOException {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/", ex -> {
            String body = answers.getOrDefault(ex.getRequestURI().getPath(), null);
            byte[] b = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            try (var out = ex.getResponseBody()) { out.write(b); }
        });
        h.start();
        closing.add(() -> h.stop(0));
        return h;
    }

    @BeforeEach
    void setUp() throws Exception {
        Set<String> portalActions = Set.of("search", "searchSemantic", "findRelated", "references", "readDocument");
        firstPort = freePort();
        secondPort = freePort();
        // The first portal has the lower port; it is the default whichever order the list gives them in.
        if (firstPort > secondPort) { int t = firstPort; firstPort = secondPort; secondPort = t; }
        first = new Portal(HITS_FULLTEXT, HITS_SEMANTIC, HITS_RELATED);
        second = new Portal(OTHER_HITS, OTHER_HITS, OTHER_HITS);
        DistributedIIActorSystem a = node("html-saurus-a", firstPort, "html-saurus-remote", portalActions);
        a.addIIActor(new PortalIIAR(first, a));
        a.startHttpServer("127.0.0.1", firstPort);
        DistributedIIActorSystem b = node("html-saurus-b", secondPort, "html-saurus-remote", portalActions);
        b.addIIActor(new PortalIIAR(second, b));
        b.startHttpServer("127.0.0.1", secondPort);

        workspacePort = freePort();
        workspace = new Workspace();
        DistributedIIActorSystem w = node("ai-workspace-test", workspacePort, "ai-workspace-remote", Set.of("listInstances"));
        w.addIIActor(new WorkspaceIIAR(workspace, w));
        w.startHttpServer("127.0.0.1", workspacePort);

        listAnswers.putAll(Map.of(
                "/api/chat-ui/peers", "[{\"name\":\"chat-ui-me\",\"host\":\"127.0.0.1\",\"actorPort\":1}]",
                "/api/nodes", "[{\"name\":\"ai-workspace-test\",\"host\":\"127.0.0.1\",\"actorPort\":" + workspacePort + ",\"kind\":\"ai-workspace\"},"
                        + "{\"name\":\"html-saurus-b\",\"host\":\"127.0.0.1\",\"actorPort\":" + secondPort + ",\"kind\":\"html-saurus\"},"
                        + "{\"name\":\"html-saurus-a\",\"host\":\"127.0.0.1\",\"actorPort\":" + firstPort + ",\"kind\":\"html-saurus\"},"
                        + "{\"name\":\"chat-ui-other\",\"host\":\"127.0.0.1\",\"actorPort\":2,\"kind\":\"chat-ui\"}]"));
        HttpServer list = http(listAnswers);
        HttpServer portalHttp = http(Map.of(
                "/api/search", HITS_FULLTEXT, "/api/search-semantic", HITS_SEMANTIC, "/api/find-related", HITS_RELATED,
                "/api/prerequisites", EDGES, "/api/prerequisite-of", "[]"));
        httpBase = "http://127.0.0.1:" + portalHttp.getAddress().getPort();

        remote = RemoteNodes.start(new IIActorSystem("tool-nodes-test"), "",
                "http://127.0.0.1:" + list.getAddress().getPort(), "chat-ui-me", freePort(), dir.resolve("ids.txt"));
        closing.add(remote);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = closing.size() - 1; i >= 0; i--) closing.get(i).close();
    }

    private DocsSource docs(String node) {
        DocsSource.Choice c = DocsSources.choose(remote, node, httpBase);
        assertNull(c.error(), c.error());
        return c.source();
    }

    @Test
    void searchDocsThroughTheNodeAnswersWhatHttpAnswers() {
        String viaNode = DocSearchTool.search("actor", 0, "all", docs(""));
        String viaHttp = DocSearchTool.search("actor", 0, "all", DocsSource.http(httpBase));
        assertEquals(viaHttp, viaNode);
        assertTrue(viaNode.contains("Actor tree") && viaNode.contains("Mailbox") && viaNode.contains("Scheduler"), viaNode);
        assertEquals(3, first.asked.size(), "one call per route: " + first.asked);
        assertTrue(first.asked.contains("search {\"q\":\"actor\"}"), first.asked.toString());
        assertTrue(first.asked.contains("findRelated {\"text\":\"actor\"}"), first.asked.toString());
        assertTrue(second.asked.isEmpty(), "the second portal is not asked");
    }

    @Test
    void theLowerPortIsTheDefault_andTheNodeArgumentPicksAnother() {
        assertEquals("html-saurus-a", docs("").where());
        String other = DocSearchTool.search("actor", 0, "fulltext", docs("html-saurus-b"));
        assertTrue(other.contains("From the second portal"), other);
        assertTrue(first.asked.isEmpty());
    }

    @Test
    void anUnknownNodeIsAnErrorThatNamesTheKnownOnes() {
        DocsSource.Choice c = DocsSources.choose(remote, "html-saurus-zzz", httpBase);
        assertNull(c.source());
        assertTrue(c.error().contains("html-saurus-zzz") && c.error().contains("html-saurus-a")
                && c.error().contains("html-saurus-b"), c.error());
    }

    @Test
    void withoutAnHtmlSaurusNodeItFallsBackToHttp() {
        DocsSource.Choice c = DocsSources.choose(null, "", httpBase);
        assertNull(c.error());
        assertEquals(httpBase, c.source().where());
        assertTrue(DocSearchTool.search("actor", 0, "fulltext", c.source()).contains("Actor tree"));
    }

    @Test
    void listReferencesThroughTheNodeAnswersWhatHttpAnswers() {
        assertEquals(ReferenceLinkTool.list("ActorTree_261118_oo01", "", "", DocsSource.http(httpBase)),
                ReferenceLinkTool.list("ActorTree_261118_oo01", "", "", docs("")));
        assertEquals(ReferenceLinkTool.list("ActorTree_261118_oo01", "backward", "", DocsSource.http(httpBase)),
                ReferenceLinkTool.list("ActorTree_261118_oo01", "backward", "", docs("")));
        String unknown = ReferenceLinkTool.list("NoSuchDoc", "forward", "", docs(""));
        assertTrue(unknown.startsWith("No documents"), unknown);
        assertTrue(first.asked.contains("references {\"id\":\"ActorTree_261118_oo01\",\"direction\":\"forward\"}"), first.asked.toString());
    }

    @Test
    void listRunningToolsAsksTheAiWorkspaceNode() {
        String all = RunningToolsTool.list(remote, "");
        assertTrue(all.contains("html-saurus") && all.contains("28001") && all.contains("READY"), all);
        assertTrue(all.contains("chat-ui-with-audit-trail-cloud-llm") && all.contains("dev"), all);
        assertTrue(all.contains("frame-viewer") && all.contains("STARTING"), all);
        assertTrue(all.startsWith("3 "), all);
        String one = RunningToolsTool.list(remote, "frame-viewer");
        assertTrue(one.startsWith("1 ") && one.contains("frame-viewer"), one);
        assertTrue(workspace.asked.contains("{\"tool\":\"frame-viewer\"}"), workspace.asked.toString());
    }

    @Test
    void listRunningToolsWithoutAnAiWorkspaceNodeSaysSo() {
        String none = RunningToolsTool.list(null, "");
        assertTrue(none.startsWith("error:") && none.contains("AI-workspace"), none);
    }

    /** ListRunningToolsShowsNodeNames_261119_oo01: the list is read again before the names are matched. */
    @Test
    void listRunningToolsShowsTheNodeOfAnInstanceStartedAMomentAgo() throws Exception {
        listAnswers.put("/api/chat-ui/peers", "[{\"name\":\"chat-ui-me\",\"host\":\"127.0.0.1\",\"actorPort\":1},"
                + "{\"name\":\"chat-ui-28010\",\"host\":\"127.0.0.1\",\"actorPort\":" + freePort() + "}]");
        String all = RunningToolsTool.list(remote, "");
        String row = all.lines().filter(l -> l.startsWith("- chat-ui-with-audit-trail-cloud-llm")).findFirst().orElse("");
        assertTrue(row.contains("node chat-ui-28010"), all);
        assertFalse(all.lines().filter(l -> l.startsWith("- html-saurus")).findFirst().orElse("").contains("node "), all);
        assertTrue(all.endsWith("other nodes this instance knows: [chat-ui-me (this instance)]"), all);
    }

    /** ListRunningToolsShowsNodeNames_261119_oo01: peers written by hand give the known names, not an error. */
    @Test
    void listRunningToolsWithPeersWrittenByHandGivesTheNamesAlone() throws Exception {
        RemoteNodes byHand = RemoteNodes.start(new IIActorSystem("by-hand-test"), "chat-ui-28014=127.0.0.1:" + freePort(),
                null, "chat-ui-me", freePort(), dir.resolve("by-hand-ids.txt"));
        closing.add(byHand);
        String out = RunningToolsTool.list(byHand, "");
        assertEquals(List.of(
                "There is no AI-workspace list of running tools here: this chat-ui instance knows no AI-workspace node."
                        + " The chat-ui nodes this instance knows, by name only:",
                "- chat-ui-me (this instance)",
                "- chat-ui-28014"), out.lines().toList());
    }

    @Test
    void toolNodesAreNotChatUiPeers() {
        assertFalse(remote.others().contains("html-saurus-a"), remote.others().toString());
        assertFalse(remote.others().contains("ai-workspace-test"), remote.others().toString());
        assertFalse(remote.knows("html-saurus-a"), "ask_chat does not send to a portal");
        assertNotNull(remote.toolNodes("html-saurus"));
        assertEquals(List.of("html-saurus-a", "html-saurus-b"),
                remote.toolNodes("html-saurus").stream().map(RemoteNodes.ToolNode::name).toList());
        assertTrue(remote.toolNodes("chat-ui").isEmpty(), "chat-ui instances come from /api/chat-ui/peers, not here");
    }
}
