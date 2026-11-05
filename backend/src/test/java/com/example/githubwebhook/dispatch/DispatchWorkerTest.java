package com.example.githubwebhook.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.githubwebhook.discord.DiscordClient;
import com.example.githubwebhook.discord.SendResult;
import com.example.githubwebhook.webhook.WebhookEvent;
import com.example.githubwebhook.webhook.WebhookEventRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Plain unit test: no Spring, no Docker. */
class DispatchWorkerTest {

    private final JobClaimService claimService = mock(JobClaimService.class);
    private final JobResultService resultService = mock(JobResultService.class);
    private final WebhookEventRepository events = mock(WebhookEventRepository.class);
    private final DiscordClient discord = mock(DiscordClient.class);
    private final DispatchWorker worker = new DispatchWorker(claimService, resultService, events, discord);

    private final ClaimedJob job1 = new ClaimedJob(1, 11, 1);
    private final ClaimedJob job2 = new ClaimedJob(2, 22, 1);
    private final SendResult ok = SendResult.delivered(200, "m");

    @BeforeEach
    void stubEvents() {
        when(events.findById(anyLong())).thenReturn(Optional.of(mock(WebhookEvent.class)));
        when(resultService.renewLease(any())).thenReturn(true);
        when(discord.send(any(), anyLong(), any(), any())).thenReturn(ok);
    }

    @Test
    void lost_lease_is_skipped() {
        when(claimService.claim()).thenReturn(List.of(job1));
        when(resultService.renewLease(job1)).thenReturn(false);

        assertThat(worker.pollOnce()).isEqualTo(1);

        verify(discord, never()).send(any(), anyLong(), any(), any());
        verify(events, never()).findById(anyLong());
        verify(resultService, never()).complete(any(), any());
    }

    @Test
    void lease_is_renewed_before_each_send() {
        when(claimService.claim()).thenReturn(List.of(job1));

        worker.pollOnce();

        InOrder order = inOrder(resultService, discord);
        order.verify(resultService).renewLease(job1);
        order.verify(discord).send(any(), anyLong(), any(), any());
        order.verify(resultService).complete(job1, ok);
    }

    @Test
    void one_bad_job_does_not_stop_the_batch() {
        when(claimService.claim()).thenReturn(List.of(job1, job2));
        WebhookEvent bad = mock(WebhookEvent.class);
        WebhookEvent good = mock(WebhookEvent.class);
        when(events.findById(11L)).thenReturn(Optional.of(bad));
        when(events.findById(22L)).thenReturn(Optional.of(good));
        when(bad.getHtmlUrl()).thenReturn("bad");
        when(good.getHtmlUrl()).thenReturn("good");
        when(discord.send(any(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            if ("bad".equals(inv.getArgument(3))) {
                throw new IllegalStateException("boom");
            }
            return ok;
        });

        assertThatCode(worker::pollOnce).doesNotThrowAnyException();

        verify(resultService, never()).complete(job1, ok);
        verify(resultService).complete(job2, ok);
    }
}
