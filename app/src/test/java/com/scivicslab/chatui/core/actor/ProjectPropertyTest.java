package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A project's own name, the id its next conversation gets, and how both survive a restart.
 * See {@code ProjectProperty_260929_oo01}.
 *
 * <p>No I/O log here: {@code ChatUiActorSystem.init()} outside CDI leaves {@code ioLogStore} null,
 * so the recording path is a no-op and these tests read the name straight off the actor. What the
 * recorded entry has to contain is checked on the JSON itself, which is the part a restart reads.
 */
@Tag("ProjectProperty_260929_oo01")
@DisplayName("Project の名前と、会話 id の採番")
class ProjectPropertyTest {

    @Test
    @DisplayName("付けた名前がそのまま読み出せる")
    void setProjectName_readsBackTheSameString() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        String outcome = system.setProjectName("project1", "ベンチマーク測定");

        assertTrue(outcome.startsWith("ok:"), outcome);
        assertEquals("ベンチマーク測定", system.getProjectName("project1"));
    }

    @Test
    @DisplayName("空文字を渡すと名前が無い状態に戻る")
    void setProjectName_blank_clearsIt() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.setProjectName("project1", "いちど付けた名前");

        system.setProjectName("project1", "   ");

        assertNull(system.getProjectName("project1"), "a blank name is no name");
    }

    @Test
    @DisplayName("知らない project に名前を付けようとすると理由が返る")
    void setProjectName_unknownProject_saysSo() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertTrue(system.setProjectName("project99", "x").startsWith("error:"));
    }

    @Test
    @DisplayName("会話を足すと 02、もう一度足すと 03 になる")
    void createChat_allocatesTheNextTwoDigitId() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertEquals("02", system.createChat("project1"), "01 is there from the start");
        assertEquals("03", system.createChat("project1"));
        assertNotNull(system.getChatSession("project1", "03"));
    }

    @Test
    @DisplayName("採番は project ごとに独立している")
    void createChat_numbersEachProjectOnItsOwn() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String second = system.createProject();
        system.createChat("project1");
        system.createChat("project1");

        assertEquals("02", system.createChat(second),
                "the other project's conversations do not advance this one's numbering");
    }

    @Test
    @DisplayName("知らない project に会話を足そうとすると断られる")
    void createChat_unknownProject_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertThrows(IllegalArgumentException.class, () -> system.createChat("project99"));
    }

    @Test
    @DisplayName("記録される JSON に名前と作業ディレクトリの両方が入る")
    void recordedJson_carriesBothValues() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        Path dir = Files.createTempDirectory("project-property-test");
        system.setProjectName("project1", "両方入る");
        system.setProjectWorkingDir("project1", dir);

        // The same object the recording path builds, from the same two reads.
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("name", system.getProjectName("project1"));
        o.put("workingDir", system.getProject("project1")
                .ask(Project::getWorkingDir).get(5, java.util.concurrent.TimeUnit.SECONDS).toString());

        assertEquals("両方入る", o.getString("name"));
        assertEquals(dir.toString(), o.getString("workingDir"));
    }

    @Test
    @DisplayName("記録された JSON から名前と作業ディレクトリが戻る")
    void recordedJson_restoresBothValues() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        Path dir = Files.createTempDirectory("project-property-restore");
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("name", "戻ってくる名前");
        o.put("workingDir", dir.toString());

        // What restoreProjectProperties does with the entry it reads.
        system.setProjectName("project1", o.getString("name"));
        system.setProjectWorkingDir("project1", Path.of(o.getString("workingDir")));

        assertEquals("戻ってくる名前", system.getProjectName("project1"));
        assertEquals(dir, system.getProject("project1")
                .ask(Project::getWorkingDir).get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("名前を付けた project は、アクターツリーで名前と id の両方を名乗る")
    void actorTree_namedProject_carriesBoth() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.setProjectName("project1", "表示される名前");

        ActorNode root = system.getActorTree();
        ActorNode project = root.children().stream()
                .filter(n -> "project1".equals(n.name())).findFirst().orElse(null);

        assertNotNull(project, "the project is in the tree");
        assertEquals("表示される名前", project.displayName());
        assertEquals("project1", project.name(), "the id stays as it is");
    }
}
