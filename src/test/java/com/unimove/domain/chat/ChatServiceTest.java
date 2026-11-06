package com.unimove.domain.chat;

import com.unimove.domain.chat.dto.ChatMessageResponse;
import com.unimove.domain.chat.dto.SendChatMessageRequest;
import com.unimove.domain.ride.RideService;
import com.unimove.domain.user.Role;
import com.unimove.domain.user.UserAccountService;
import com.unimove.domain.user.dto.PassengerPublicInfo;
import com.unimove.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ChatMessageRepository repository;
    @Mock RideService rideService;
    @Mock ChatSseHub hub;
    @Mock UserAccountService userAccountService;
    @InjectMocks ChatService chatService;

    private final UUID rideId = UUID.randomUUID();
    private final AuthenticatedUser pax =
            new AuthenticatedUser(UUID.randomUUID(), "p@x.com", Role.PASSAGEIRO, "remanso-ba");
    private final UUID motoristaId = UUID.randomUUID();

    @Test
    @DisplayName("send: a mensagem sai com o primeiro nome de quem enviou")
    void sendCarriesSenderName() {
        when(rideService.assertChatAllowed(pax, rideId)).thenReturn(Role.PASSAGEIRO);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userAccountService.findPublicInfo(pax.userId()))
                .thenReturn(Optional.of(new PassengerPublicInfo("Ana", null, null)));

        ChatMessageResponse r = chatService.send(pax, rideId, new SendChatMessageRequest(" oi "));

        assertThat(r.senderName()).isEqualTo("Ana");
        assertThat(r.body()).isEqualTo("oi");
        verify(hub).broadcast(rideId, r);
    }

    @Test
    @DisplayName("history: nome por remetente, um lookup por pessoa; conta sumida vem sem nome")
    void historyResolvesEachSenderOnce() {
        when(rideService.assertChatAllowed(pax, rideId)).thenReturn(Role.PASSAGEIRO);
        when(repository.findByRideIdOrderBySeqAsc(rideId)).thenReturn(List.of(
                msg(pax.userId(), Role.PASSAGEIRO), msg(motoristaId, Role.MOTORISTA), msg(pax.userId(), Role.PASSAGEIRO)));
        when(userAccountService.findPublicInfo(pax.userId()))
                .thenReturn(Optional.of(new PassengerPublicInfo("Ana", null, null)));
        when(userAccountService.findPublicInfo(motoristaId)).thenReturn(Optional.empty());

        List<ChatMessageResponse> history = chatService.history(pax, rideId);

        assertThat(history).extracting(ChatMessageResponse::senderName).containsExactly("Ana", null, "Ana");
        verify(userAccountService, times(1)).findPublicInfo(pax.userId());
    }

    private ChatMessage msg(UUID sender, Role role) {
        ChatMessage m = new ChatMessage();
        m.setRideId(rideId);
        m.setSenderId(sender);
        m.setSenderRole(role);
        m.setBody("x");
        return m;
    }
}
