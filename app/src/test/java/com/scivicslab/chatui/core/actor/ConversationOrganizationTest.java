package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.agent.CreateChatTool;
import com.scivicslab.chatui.agent.OrganizationTools;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The organization as the conversations' tools reach it, on a real actor system: create_chat puts the new
 * conversation under its creator, the limit stops a full superior, list_chats shows the lines, a
 * conversation with subordinates is not deleted, and ask_chat finds the organization actor by its name
 * ({@code ConversationReportingLinesTests_261108_oo01}).
 */
@Tag("ConversationReportingLinesTests_261108_oo01")
@DisplayName("会話の組織 — 道具から見た上下関係")
class ConversationOrganizationTest {

    private static ChatUiActorSystem started() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        return system;
    }

    @Test
    @DisplayName("create_chat で作った会話は、作った会話を上司にする")
    void createChatPutsTheNewConversationUnderItsCreator() {
        ChatUiActorSystem system = started();

        String made = CreateChatTool.create(null, system, "project1", "01", "openai-compat", null, "");

        assertTrue(made.startsWith("ok: created project1/chat-02") && made.endsWith("reporting to chat-01"), made);
        String list = OrganizationTools.list(system, "project1", "01");
        assertTrue(list.contains("chat-01 (you) (liaison); superior: none; subordinates: 1"), list);
        assertTrue(list.contains("chat-02; superior: chat-01; subordinates: 0"), list);
    }

    @Test
    @DisplayName("上限に達した上司の下には作らず、会話も残さない")
    void aFullSuperiorGetsNoNewConversation() {
        ChatUiActorSystem system = started();
        assertEquals("ok", system.setOrganizationSettings("project1", null, 2));
        CreateChatTool.create(null, system, "project1", "01", "openai-compat", null, "");
        CreateChatTool.create(null, system, "project1", "01", "openai-compat", null, "");

        String refused = CreateChatTool.create(null, system, "project1", "01", "openai-compat", null, "");

        assertTrue(refused.contains("chat-01 already has 2 subordinates") && refused.contains("no conversation was created"), refused);
        assertFalse(system.getChatNames().contains("project1/chat-04"), "nothing was left behind");
        String deeper = CreateChatTool.create(null, system, "project1", "01", "openai-compat", null, "02");
        assertTrue(deeper.endsWith("reporting to chat-02"), "under a subordinate with room: " + deeper);
    }

    @Test
    @DisplayName("set_superior は付け替え、ループを断る")
    void setSuperiorReassignsAndRefusesALoop() {
        ChatUiActorSystem system = started();
        system.createChat("project1");
        system.createChat("project1");
        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "02", "01").startsWith("ok"));
        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "03", "02").startsWith("ok"));

        String loop = OrganizationTools.setSuperior(system, "project1", "01", "02", "03");
        assertTrue(loop.startsWith("error: refused") && loop.contains("reports to chat-02"), loop);

        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "03", "none").startsWith("ok"));
        assertTrue(OrganizationTools.list(system, "project1", "01").contains("chat-03; superior: none"));
    }

    @Test
    @DisplayName("部下を持つ会話は消せず、部下がいなくなれば消せる")
    void aConversationWithSubordinatesIsNotDeleted() {
        ChatUiActorSystem system = started();
        system.createChat("project1");
        system.createChat("project1");
        system.setSuperior("project1", "03", "02");

        ChatUiActorSystem.ChatDeletion refused = system.deleteChat("project1", "02");
        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.HAS_NESTED, refused.kind());
        assertTrue(refused.reason().contains("chat-03"), refused.reason());

        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, system.deleteChat("project1", "03").kind());
        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, system.deleteChat("project1", "02").kind());
    }

    @Test
    @DisplayName("窓口を替えられ、立場の段落がそれを言う")
    void theLiaisonCanBeChangedAndThePositionSaysSo() {
        ChatUiActorSystem system = started();
        system.createChat("project1");
        system.setChatName("project1", "02", "front desk");
        assertTrue(system.isLiaison("project1", "01"));

        assertEquals("ok", system.setOrganizationSettings("project1", "02", null));
        system.setSuperior("project1", "01", "02");

        assertTrue(system.isLiaison("project1", "02"));
        String position = system.positionOf("project1", "01");
        assertTrue(position.contains("Your superior is chat-02 \"front desk\""), position);
        assertTrue(system.setOrganizationSettings("project1", "09", null).startsWith("error:"), "no such conversation");
    }

    @Test
    @DisplayName("ask_chat は組織のアクターを名前で見つけ、上司からの送信だと分かる")
    void askChatFindsTheOrganizationActorByName() {
        IIActorSystem actors = new IIActorSystem("organization-lookup");
        Organization org = new Organization();
        org.setSuperior("02", "01");
        actors.actorOf(ChatUiActorSystem.organizationActorName("project1"), org);

        assertTrue(AskChatTool.isSuperiorOf(actors, "project1", "01", "project1/chat-02"));
        assertFalse(AskChatTool.isSuperiorOf(actors, "project1", "02", "project1/chat-01"), "upward is not");
        assertFalse(AskChatTool.isSuperiorOf(actors, "project1", "01", "project2/chat-02"), "another project");
    }
}
