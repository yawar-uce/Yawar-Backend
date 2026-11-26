package ec.yawar.donors.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ec.yawar.donors.application.port.ConsentRepository;
import ec.yawar.donors.application.port.DonorRepository;
import ec.yawar.donors.domain.Consent;
import ec.yawar.donors.domain.ConsentPurpose;
import ec.yawar.donors.domain.Donor;
import ec.yawar.donors.domain.DonorRegistered;
import ec.yawar.donors.domain.RegistrationSource;
import ec.yawar.donors.domain.UserId;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GetOrCreateDonorUseCaseTest {

    private final DonorRepository donors = mock(DonorRepository.class);
    private final ConsentRepository consents = mock(ConsentRepository.class);
    private final Outbox outbox = mock(Outbox.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
    private final GetOrCreateDonorUseCase useCase = new GetOrCreateDonorUseCase(donors, consents, outbox, clock, "2026-1");
    private final UserId user = new UserId(UUID.randomUUID());

    @Test
    void firstCallWithConsentCreatesDonorConsentAndEvent() {
        when(donors.findByUserId(user)).thenReturn(Optional.empty());
        when(donors.insertIfAbsent(any())).thenReturn(true);

        DonorProfile profile = useCase.execute(user, true, RegistrationSource.PASSWORD);

        assertThat(profile.consents()).hasSize(1);
        assertThat(profile.consents().get(0).policyVersion()).isEqualTo("2026-1");
        ArgumentCaptor<OutboxMessage> sent = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).append(sent.capture());
        assertThat(sent.getValue().eventType()).isEqualTo("donors.donor.registered");
        assertThat(sent.getValue().aggregateVersion()).isZero();
        assertThat(sent.getValue().payload()).isInstanceOf(DonorRegistered.class);
        verify(consents).insert(any(), any(Consent.class));
    }

    @Test
    void withoutConsentNothingIsCreated() {
        when(donors.findByUserId(user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(user, false, RegistrationSource.PASSWORD))
                .isInstanceOf(ConsentRequiredException.class);

        verify(donors, never()).insertIfAbsent(any());
        verifyNoInteractions(consents, outbox);
    }

    @Test
    void existingDonorIsReturnedWithoutEvent() {
        Donor donor = Donor.register(user);
        Consent consent = Consent.grant(ConsentPurpose.DATA_PROCESSING, "2026-1", clock.instant());
        when(donors.findByUserId(user)).thenReturn(Optional.of(donor));
        when(consents.findByDonor(donor.id())).thenReturn(List.of(consent));

        DonorProfile profile = useCase.execute(user, false, RegistrationSource.PASSWORD);

        assertThat(profile.donor()).isEqualTo(donor);
        verifyNoInteractions(outbox);
    }

    @Test
    void losingTheRaceReturnsTheWinnersDonorWithoutEvent() {
        Donor winner = Donor.register(user);
        when(donors.findByUserId(user)).thenReturn(Optional.empty(), Optional.of(winner));
        when(donors.insertIfAbsent(any())).thenReturn(false);
        when(consents.findByDonor(winner.id())).thenReturn(List.of());

        DonorProfile profile = useCase.execute(user, true, RegistrationSource.PASSWORD);

        assertThat(profile.donor()).isEqualTo(winner);
        verifyNoInteractions(outbox);
        verify(consents, never()).insert(any(), any());
    }
}
