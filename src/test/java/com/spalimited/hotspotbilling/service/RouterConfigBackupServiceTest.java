package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterBackup;
import com.spalimited.hotspotbilling.repository.RouterBackupRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Keeping a copy of what is on each router.
 *
 * <p>The interesting cases are all about not making things worse. A router that
 * cannot be reached, or that answers with nothing, must not be allowed to
 * replace a good backup with an empty one or to make an old backup look fresh --
 * because the whole point of this feature is the night somebody needs the config
 * back, and a backup that quietly became blank is worse than no backup at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterConfigBackupServiceTest {

    @Mock
    private RouterRepository routers;

    @Mock
    private RouterBackupRepository backups;

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private AuditService audit;

    @InjectMocks
    private RouterConfigBackupService service;

    private Router router;
    private final List<RouterBackup> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        router = new Router();
        router.setId(3L);
        router.setName("Westlands Site");
        router.setHost("10.90.0.2");
        router.setEnabled(true);

        stored.clear();
        when(routers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(backups.save(any())).thenAnswer(i -> {
            RouterBackup b = i.getArgument(0);
            if (b.getId() == null) {
                b.setId((long) (stored.size() + 1));
                stored.add(b);
            }
            return b;
        });
        when(backups.findFirstByRouterIdOrderByFirstSeenAtDesc(3L)).thenAnswer(i ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
    }

    private void routerReturns(String method, String text) {
        when(mikrotikService.exportConfig(router))
                .thenReturn(new MikrotikService.ConfigExport(method, text));
    }

    @Test
    @DisplayName("the first backup is stored, and the router records that it worked")
    void firstBackup() {
        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\n");

        RouterConfigBackupService.Outcome outcome = service.backup(router);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.changed()).isTrue();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getMethod()).isEqualTo("EXPORT");
        assertThat(stored.get(0).getLineCount()).isEqualTo(3);
        assertThat(router.getConfigBackupAt()).isNotNull();
        assertThat(router.getConfigBackupError()).isNull();
    }

    @Test
    @DisplayName("an unchanged config stores no second copy but does record being seen")
    void unchangedStoresNothingNew() {
        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\n");
        service.backup(router);
        java.time.Instant firstSeen = stored.get(0).getFirstSeenAt();

        RouterConfigBackupService.Outcome again = service.backup(router);

        assertThat(again.ok()).isTrue();
        assertThat(again.changed()).isFalse();
        assertThat(stored).hasSize(1);
        // first_seen stays put -- it is when this version appeared, not when we
        // last looked.
        assertThat(stored.get(0).getFirstSeenAt()).isEqualTo(firstSeen);
        // last_seen moves, which is the only thing separating "unchanged since
        // March" from "unreachable since March".
        assertThat(stored.get(0).getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    @DisplayName("a changed config is kept alongside the old one, not instead of it")
    void changeKeepsBoth() {
        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\n");
        service.backup(router);

        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\nadd address=10.0.1.1/24\n");
        RouterConfigBackupService.Outcome outcome = service.backup(router);

        assertThat(outcome.changed()).isTrue();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getContent()).doesNotContain("10.0.1.1");
        assertThat(stored.get(1).getContent()).contains("10.0.1.1");
    }

    @Test
    @DisplayName("an unreachable router records the reason and stores nothing")
    void unreachable() {
        when(mikrotikService.exportConfig(router))
                .thenThrow(new IllegalStateException("connection timed out"));

        RouterConfigBackupService.Outcome outcome = service.backup(router);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("connection timed out");
        assertThat(stored).isEmpty();
        assertThat(router.getConfigBackupError()).contains("connection timed out");
        // Not touched: claiming a backup happened tonight is the specific lie
        // this feature exists to prevent.
        assertThat(router.getConfigBackupAt()).isNull();
    }

    @Test
    @DisplayName("a router that answers with nothing does not overwrite a good backup")
    void emptyAnswerIsAFailure() {
        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\n");
        service.backup(router);
        java.time.Instant good = router.getConfigBackupAt();

        routerReturns("EXPORT", "   ");
        RouterConfigBackupService.Outcome outcome = service.backup(router);

        assertThat(outcome.ok()).isFalse();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getContent()).contains("10.0.0.1");
        // The timestamp still points at the last real backup, and the error says
        // why tonight is not one.
        assertThat(router.getConfigBackupAt()).isEqualTo(good);
        assertThat(router.getConfigBackupError()).isNotNull();
        // Once, for the real backup at the top. A blank answer is not a change
        // and must not appear in the audit trail as one.
        verify(audit, times(1)).system(any(), any());
    }

    @Test
    @DisplayName("the same text captured two different ways is still a change")
    void methodChangeIsRecorded() {
        // Not a contrivance: a RouterOS upgrade can make /export start working
        // where it did not before, and the two produce very different text. What
        // matters is that the stored copy says which it is.
        routerReturns("SECTIONS", "/ip/address\n    address=10.0.0.1/24\n");
        service.backup(router);
        routerReturns("EXPORT", "/ip address\nadd address=10.0.0.1/24\n");
        service.backup(router);

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getMethod()).isEqualTo("SECTIONS");
        assertThat(stored.get(1).getMethod()).isEqualTo("EXPORT");
    }

    // --- the diff ---

    @Test
    @DisplayName("a diff marks what was added and removed, and leaves the rest alone")
    void diffMarksChanges() {
        List<Map<String, String>> lines = service.diff(
                "a\nb\nc\n",
                "a\nc\nd\n");

        assertThat(lines).extracting(l -> l.get("mark") + l.get("text"))
                .containsExactly(" a", "-b", " c", "+d", " ");
    }

    @Test
    @DisplayName("a moved block reads as one move rather than as changes everywhere")
    void diffHandlesRepeatedLines() {
        // Set arithmetic would report nothing changed here, because both sides
        // hold the same three lines. A firewall rule moved from the bottom to the
        // top of the chain is a real change and this has to show it.
        List<Map<String, String>> lines = service.diff(
                "accept\ndrop\nlog",
                "log\naccept\ndrop");

        long added = lines.stream().filter(l -> "+".equals(l.get("mark"))).count();
        long removed = lines.stream().filter(l -> "-".equals(l.get("mark"))).count();
        assertThat(added).isEqualTo(1);
        assertThat(removed).isEqualTo(1);
    }

    @Test
    @DisplayName("two identical configs produce a diff with nothing marked")
    void diffOfIdentical() {
        List<Map<String, String>> lines = service.diff("a\nb\n", "a\nb\n");

        assertThat(lines).allSatisfy(l -> assertThat(l.get("mark")).isEqualTo(" "));
    }

    @Test
    @DisplayName("configs too large to compare say so instead of hanging")
    void diffGivesUpLoudly() {
        String huge = "line\n".repeat(5000);

        List<Map<String, String>> lines = service.diff(huge, huge + "extra\n");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("mark")).isEqualTo("note");
        assertThat(lines.get(0).get("text")).contains("too large");
    }
}
