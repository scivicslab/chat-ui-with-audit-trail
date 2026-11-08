package com.scivicslab.chatui.core.actor;

import java.util.List;

/**
 * The {@link TeamDesk} of a running instance: each operation is the {@link ChatUiActorSystem}'s own, and nothing else of
 * it is reachable through this object ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 */
final class SystemTeamDesk implements TeamDesk {

    private final ChatUiActorSystem system;

    SystemTeamDesk(ChatUiActorSystem system) {
        this.system = system;
    }

    @Override
    public List<Provider> providers() {
        return system.providerOptions().stream().map(o -> new Provider(o.value(), o.kind(), o.toolSet())).toList();
    }

    @Override public String rolesText(String projectId) { return system.rolesTextOf(projectId); }

    @Override public String liaisonOf(String projectId) { return system.liaisonIdOf(projectId); }

    @Override public int subordinateLimit(String projectId) { return system.subordinateLimitOf(projectId); }

    @Override public int roomLeft(String projectId, String chatId) { return system.roomLeftUnder(projectId, chatId); }

    @Override public String createChat(String projectId) { return system.createChat(projectId); }

    @Override
    public void setProvider(String projectId, String chatId, Provider provider) {
        system.setProvider(projectId, chatId, provider.kind(), provider.toolSet());
    }

    @Override public List<String> modelNames(String projectId, String chatId) { return system.modelNamesOf(projectId, chatId); }

    @Override
    public void setModel(String projectId, String chatId, String model) {
        if (!system.setModel(projectId, chatId, model)) {
            throw new IllegalStateException("chat-" + chatId + " has no provider to set the model on");
        }
    }

    @Override
    public String setChatName(String projectId, String chatId, String name) {
        String outcome = system.setChatName(projectId, chatId, name);
        return outcome.startsWith("error:") ? outcome : null;
    }

    @Override
    public String setSuperior(String projectId, String chatId, String superiorChatId) {
        String outcome = system.setSuperior(projectId, chatId, superiorChatId);
        return outcome.startsWith("error:") ? outcome : null;
    }

    @Override
    public void setTimekeeperLimits(String projectId, String chatId, long urgentWaitMinutes, long childReplyWarnMinutes) {
        system.setTimekeeperLimits(projectId, chatId, urgentWaitMinutes, childReplyWarnMinutes);
    }

    @Override
    public boolean deleteChat(String projectId, String chatId) {
        return system.deleteChat(projectId, chatId).kind() == ChatUiActorSystem.ChatDeletion.Kind.DELETED;
    }

    @Override
    public String notifyConversation(String projectId, String chatId, String source, String text) {
        return system.notifyConversation(projectId, chatId, source, text);
    }
}
