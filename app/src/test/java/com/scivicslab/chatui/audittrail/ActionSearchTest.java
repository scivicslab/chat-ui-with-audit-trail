package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Finding an action by its name alone, across every actor a workflow may name
 * ({@code MethodSearch_261011_oo01}).
 */
@Tag("MethodSearch_261011_oo01")
@DisplayName("メソッド名だけで説明を引く")
class ActionSearchTest {

    static class Runner {
        @Action("putJson") public ActionResult putJson(String a) { return null; }
        @Action("print") public ActionResult print(String a) { return null; }
    }

    static class Chat {
        @Action("putJsonPath") public ActionResult putJsonPath(String a) { return null; }
        @Action("sendPrompt") public ActionResult sendPrompt(String a) { return null; }
    }

    static class Store {
        @Action("putJson") public ActionResult putJson(String a) { return null; }
        @Action("outputJson") public ActionResult outputJson(String a) { return null; }
    }

    private static final Map<String, Class<?>> CLASSES = Map.of(
            "this", Runner.class,
            "project1/chat-01", Chat.class,
            "project1/chat-02", Chat.class,
            "store", Store.class);
    private static final List<String> ACTORS = List.of("this", "project1/chat-01", "project1/chat-02", "store");

    private static List<ActionSearch.Match> search(String q) {
        return ActionSearch.search(q, ACTORS, CLASSES::get, (cls, action) -> cls.getSimpleName() + "." + action + " does it.");
    }

    @Test
    @DisplayName("大文字小文字の違う部分文字列で見つかる")
    void findsByCaseInsensitiveSubstring() {
        List<ActionSearch.Match> found = search("PROMPT");
        assertEquals(1, found.size());
        assertEquals("sendPrompt", found.get(0).action());
        assertEquals("Chat", found.get(0).className());
        assertEquals("Chat.sendPrompt does it.", found.get(0).description());
    }

    @Test
    @DisplayName("同じクラスの actor は 1 件にまとまり、呼べる名前が全部並ぶ")
    void actorsOfOneClassShareOneMatch() {
        List<ActionSearch.Match> found = search("sendprompt");
        assertEquals(List.of("project1/chat-01", "project1/chat-02"), found.get(0).actors());
    }

    @Test
    @DisplayName("同じ名前のメソッドでもクラスが違えば別の件になる")
    void sameNameInAnotherClassIsAnotherMatch() {
        List<ActionSearch.Match> exact = search("putJson").stream().filter(m -> m.action().equals("putJson")).toList();
        assertEquals(2, exact.size());
        assertEquals(List.of("Runner", "Store"), exact.stream().map(ActionSearch.Match::className).toList());
    }

    @Test
    @DisplayName("名前の一致、前方一致、部分一致の順に並ぶ")
    void exactThenPrefixThenSubstring() {
        List<String> order = search("json").stream().map(m -> m.action() + "@" + m.className()).toList();
        // no exact "json"; prefix: none; substring: outputJson, putJson x2, putJsonPath -- by name, then class
        assertEquals(List.of("outputJson@Store", "putJson@Runner", "putJson@Store", "putJsonPath@Chat"), order);

        List<String> put = search("putjson").stream().map(m -> m.action() + "@" + m.className()).toList();
        // outputJson contains "putjson" too, so it comes last as a substring match
        assertEquals(List.of("putJson@Runner", "putJson@Store", "putJsonPath@Chat", "outputJson@Store"), put);
        assertTrue(search("put").get(0).action().startsWith("put"));
    }

    static class Base {
        @Action("putState") public ActionResult putState(String a) { return null; }
        @Action("describeSelf") public ActionResult describeSelf(String a) { return null; }
    }

    static class LeftChild extends Base { }

    static class RightChild extends Base {
        @Override @Action("describeSelf") public ActionResult describeSelf(String a) { return null; }
    }

    @Test
    @DisplayName("受け継いだメソッドは、宣言しているクラスの 1 件にまとまる")
    void inheritedActionIsOneMatchUnderItsDeclaringClass() {
        Map<String, Class<?>> classes = Map.of("left", LeftChild.class, "right", RightChild.class);
        List<ActionSearch.Match> found = ActionSearch.search("putState", List.of("left", "right"), classes::get,
                (cls, action) -> cls.getSimpleName() + "." + action);
        assertEquals(1, found.size());
        assertEquals("Base", found.get(0).className());
        assertEquals("Base.putState", found.get(0).description());
        assertEquals(List.of("left", "right"), found.get(0).actors());
    }

    @Test
    @DisplayName("付け直したメソッドは、付け直したクラスの件になる")
    void redeclaredActionIsItsOwnMatch() {
        Map<String, Class<?>> classes = Map.of("left", LeftChild.class, "right", RightChild.class);
        List<String> found = ActionSearch.search("describeSelf", List.of("left", "right"), classes::get, (c, a) -> "")
                .stream().map(m -> m.className() + ":" + m.actors()).toList();
        assertEquals(List.of("Base:[left]", "RightChild:[right]"), found);
    }

    @Test
    @DisplayName("空の検索語では何も返らない")
    void blankQueryFindsNothing() {
        assertTrue(search("").isEmpty());
        assertTrue(search("   ").isEmpty());
        assertTrue(search(null).isEmpty());
    }
}
