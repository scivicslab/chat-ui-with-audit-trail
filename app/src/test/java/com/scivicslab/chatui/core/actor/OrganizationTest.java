package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The rules of one project's organization, on the plain object alone: who reports to whom, what is
 * refused, and what survives being written out and read back ({@code ConversationReportingLinesTests_261108_oo01}).
 */
@Tag("ConversationReportingLinesTests_261108_oo01")
@DisplayName("Organization — 上司の表と、その決まり")
class OrganizationTest {

    @Test
    @DisplayName("上司を決めると、部下は上司の表から引ける")
    void subordinatesAreReadFromTheSuperiorTable() {
        Organization o = new Organization();
        assertNull(o.setSuperior("02", "01"));
        assertNull(o.setSuperior("03", "01"));
        assertNull(o.setSuperior("05", "02"));

        assertEquals(List.of("02", "03"), o.subordinatesOf("01"));
        assertEquals(List.of("05"), o.subordinatesOf("02"));
        assertEquals("02", o.superiorOf("05"));
        assertTrue(o.isSuperiorOf("02", "05"));
        assertFalse(o.isSuperiorOf("01", "05"), "only the direct superior counts");
    }

    @Test
    @DisplayName("付け替えると、前の上司の部下から消える")
    void reassigningMovesTheSubordinate() {
        Organization o = new Organization();
        o.setSuperior("02", "01");
        o.setSuperior("03", "01");
        o.setSuperior("05", "02");

        assertNull(o.setSuperior("05", "03"));

        assertEquals(List.of(), o.subordinatesOf("02"));
        assertEquals(List.of("05"), o.subordinatesOf("03"));
    }

    @Test
    @DisplayName("ループになる付け方、自分自身、窓口への上司は断られ、何も変わらない")
    void loopsSelfAndTheLiaisonAreRefused() {
        Organization o = new Organization();
        o.setSuperior("02", "01");
        o.setSuperior("05", "02");

        assertTrue(o.setSuperior("02", "05").contains("reports to chat-02"), "02 is above 05");
        assertTrue(o.setSuperior("03", "03").contains("its own superior"));
        assertTrue(o.setSuperior("01", "02").contains("liaison"), "01 is the liaison by default");
        assertEquals("01", o.superiorOf("02"), "nothing changed");
    }

    @Test
    @DisplayName("上限に達した上司は断り、余裕のある部下を名指す")
    void aFullSuperiorIsRefusedNamingSubordinatesWithRoom() {
        Organization o = new Organization();
        o.setMaxSubordinates(2);
        o.setSuperior("02", "01");
        o.setSuperior("03", "01");
        o.setSuperior("04", "02");
        o.setSuperior("05", "02");

        String refusal = o.setSuperior("06", "01");

        assertTrue(refusal.contains("chat-01 already has 2 subordinates"), refusal);
        assertTrue(refusal.contains("chat-03") && !refusal.contains("chat-02"),
                "chat-02 is full too; chat-03 has room: " + refusal);
        assertNull(o.superiorOf("06"));
        assertNull(o.setSuperior("06", "03"), "the subordinate with room takes it, one level deeper");
    }

    @Test
    @DisplayName("窓口を替えると、新しい窓口は上司を持たない")
    void theNewLiaisonLosesItsSuperior() {
        Organization o = new Organization();
        o.setSuperior("02", "01");

        assertNull(o.setLiaison("02"));

        assertEquals("02", o.liaison());
        assertNull(o.superiorOf("02"));
    }

    @Test
    @DisplayName("JSON に書いて読み戻すと同じ組織になり、消えた会話は捨てられる")
    void theOrganizationSurvivesItsJsonAndDropsVanishedConversations() {
        Organization o = new Organization();
        o.setMaxSubordinates(3);
        o.setSuperior("02", "01");
        o.setSuperior("05", "02");
        o.setSuperior("07", "02");

        Organization back = new Organization();
        back.loadJson(new org.json.JSONObject(o.toJson().toString()));
        back.retainOnly(List.of("01", "02", "05"));

        assertEquals(3, back.maxSubordinates());
        assertEquals("01", back.liaison());
        assertEquals(List.of("05"), back.subordinatesOf("02"), "chat-07 did not come back");
    }

    @Test
    @DisplayName("立場の段落は、上司・部下・窓口を名前付きで言い、所属の無い会話には出ない")
    void thePositionParagraphNamesSuperiorSubordinatesAndLiaison() {
        Organization o = new Organization();
        o.setSuperior("02", "01");
        o.setSuperior("05", "02");
        java.util.function.Function<String, String> label = c -> "chat-" + c + " \"n" + c + "\"";

        String manager = o.positionText("project1", "02", label);
        assertTrue(manager.contains("you are chat-02 \"n02\""), manager);
        assertTrue(manager.contains("Your superior is chat-01 \"n01\""), manager);
        assertTrue(manager.contains("Your subordinates are chat-05 \"n05\""), manager);
        assertTrue(manager.contains("ask your superior with ask_chat only what you cannot decide"), manager);

        String liaison = o.positionText("project1", "01", label);
        assertTrue(liaison.contains("You are the liaison"), liaison);
        assertFalse(liaison.contains("Your superior"), liaison);

        assertNull(o.positionText("project1", "09", label), "a conversation outside the organization is told nothing");
    }
}
